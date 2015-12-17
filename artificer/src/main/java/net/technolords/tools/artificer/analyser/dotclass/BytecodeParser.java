package net.technolords.tools.artificer.analyser.dotclass;

import net.technolords.tools.artificer.analyser.dotclass.specification.ConstantPoolConstant;
import net.technolords.tools.artificer.analyser.dotclass.specification.ConstantPoolConstants;
import net.technolords.tools.artificer.analyser.dotclass.specification.ConstantPoolInfoFragment;
import net.technolords.tools.artificer.analyser.dotclass.specification.JavaSpecification;
import net.technolords.tools.artificer.analyser.dotclass.specification.JavaSpecifications;
import net.technolords.tools.artificer.domain.bytecode.Constant;
import net.technolords.tools.artificer.domain.bytecode.ConstantInfo;
import net.technolords.tools.artificer.domain.bytecode.ConstantPool;
import net.technolords.tools.artificer.domain.resource.Resource;
import net.technolords.tools.artificer.exception.ArtificerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Created by Technolords on 2015-Nov-25.
 *
 * Every '.class' file starts off with the following:
 * - Magic Number [4 bytes]
 * - Version Information [4 bytes]
 *
 * javac -target 1.1 ==> CA FE BA BE 00 03 00 2D
 * javac -target 1.2 ==> CA FE BA BE 00 00 00 2E
 * javac -target 1.3 ==> CA FE BA BE 00 00 00 2F
 * javac -target 1.4 ==> CA FE BA BE 00 00 00 30
 * javac -target 1.5 ==> CA FE BA BE 00 00 00 31
 * javac -target 1.6 ==> CA FE BA BE 00 00 00 32
 * javac -target 1.7 ==> CA FE BA BE 00 00 00 33
 * javac -target 1.8 ==> CA FE BA BE 00 00 00 34
 *
 * Legend:
 * - u1: unsigned one byte quantity, to be read as: readUnsignedByte
 * - u2: unsigned two byte quantity, to be read as: readUnsignedShort
 * - u4: unsigned four byte quantity, to be read as: readInt
 * - u8: unsigned eight byte quantity, to be read as: readLong
 */
public class BytecodeParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(BytecodeParser.class);
    private String referenceFile;
    private Map<String, JavaSpecification> lookupMap;

    public BytecodeParser(String referenceFile) {
        this.referenceFile = referenceFile;
    }

    /**
     * Auxiliary method to initialize the lookup map. It uses the default classloader to obtain
     * an inputstream as reference for the XML file and then JAXB will use this to unmarshall this
     * to an instance of the JavaSpecifications class.
     *
     * @throws ArtificerException
     *  When unmarshalling the XML file fails.
     */
    public void initializeSpecifications() throws ArtificerException {
        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(JavaSpecifications.class);
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(this.referenceFile);
            JavaSpecifications javaSpecifications = (JavaSpecifications) unmarshaller.unmarshal(inputStream);
            for(JavaSpecification javaSpecification : javaSpecifications.getJavaSpecifications()) {
                this.lookupMap.put(javaSpecification.getVersion(), javaSpecification);
            }
            LOGGER.debug("Total java specifications initialized: " + this.lookupMap.size());
        } catch (JAXBException e) {
            throw new ArtificerException(e);
        }
    }

    /**
     * An auxiliary method to analyse the byte code of the class to determine the makeup. This is done by
     * reading bytes from the DataInputStream representing the resource. According to the JVM specification the
     * class file has the following structure (example is fetched from JVM 8 specification):
     *
     * ClassFile {
     *     u4               magic_number
     *     u2               minor_version
     *     u2               major_version
     *     u2               constant_pool_count
     *     cp_info          constant_pool[constant_pool_count -1]
     *     u2               access_flags
     *     u2               this_class
     *     u2               super_class
     *     u2               interfaces_count
     *     u2               interfaces[interfaces_count]
     *     u2               fields_count
     *     field_info       fields[fields_count]
     *     u2               methods_count
     *     method_info      methods[methods_count]
     *     u2               attributes_count
     *     attributes_info  attributes[attributes_count]
     * }
     *
     * Legend:
     * - u1: unsigned one byte quantity, to be read as: readUnsignedByte
     * - u2: unsigned two byte quantity, to be read as: readUnsignedShort
     * - u4: unsigned four byte quantity, to be read as: readInt
     * - u8: unsigned eight byte quantity, to be read as: readLong
     *
     * The constant pool is of particular interest, as this contains references of classes.
     *
     * @param resource
     *  The resource associated with the determination of the referenced classes.
     */
    public void analyseBytecode(Resource resource) {
        try {
            if(this.lookupMap == null) {
                this.lookupMap = new HashMap<>();
                this.initializeSpecifications();
            }
            LOGGER.info("About to analyse byte code of: " + resource.getName() + ", for JVM spec: " + resource.getCompiledVersion());
            DataInputStream dataInputStream = new DataInputStream(Files.newInputStream(resource.getPath()));
            // Absorb magic number, minor and major version
            this.absorbOverhead(dataInputStream);
            // Extract the constant pool
            ConstantPool constantPool = this.extractConstantPool(dataInputStream, resource.getCompiledVersion());
            resource.setConstantPool(constantPool);
        } catch (IOException e) {
            LOGGER.error("Unable to parse the class: " + resource.getName(), e);
        } catch (ArtificerException e) {
            LOGGER.error("Unable to initialize lookup map" + e.getMessage(), e);
        }
    }

    /**
     * An auxiliary method to read some bytes from the stream and absorb them as the information is not
     * needed in this phase (and is already known). This phase is all about the deeper stuff of the .class file.
     *
     * The previous phase, done by the JavaSpecificationManager, was about the initial scanning of the .class files
     * for basic reporting purposes.
     *
     * @param dataInputStream
     *  The byte stream associated with the resource (aka .class file).
     * @throws IOException
     *  When reading bytes from the stream fails.
     */
    protected void absorbOverhead(DataInputStream dataInputStream) throws IOException {
        // Absorb magic number (as it is already known)
        dataInputStream.readInt();
        // Absorb minor and major number
        dataInputStream.readInt();
    }

    /**
     * An auxiliary method to extract the constant pool from the byte stream. Based on the detected
     * JVM version, the associated specification is fetched to construct the constant pool. The
     * constant pool returned consists of a number of constants.
     *
     * According to the JVM specification, it mentions that the value of the constant_pool_count is equal
     * to the number of entries in the constant_pool table plus one.
     *
     * A constant_pool index is considered valid if it is greater than zero and less than the
     * constant_pool_count, with the exceptions for constants of type long and double.
     *
     * The constant_pool table is indexed from 1 to constant_pool_count - 1
     *
     * All constant pool entries have the following general format:
     *
     * cp_info {
     *     u1               tag
     *     u1               info[]
     * }
     *
     * Legend:
     * - u1: unsigned one byte quantity, to be read as: readUnsignedByte
     * - u2: unsigned two byte quantity, to be read as: readUnsignedShort
     * - u4: unsigned four byte quantity, to be read as: readInt
     * - u8: unsigned eight byte quantity, to be read as: readLong
     *
     * @param dataInputStream
     *  The byte stream associated with the constant pool extraction.
     * @param compiledVersion
     *  The compiled version associated with the resource (aka .class file).
     * @return
     *  The extracted constant pool.
     * @throws IOException
     *  When reading bytes from the stream fails.
     */
    protected ConstantPool extractConstantPool(DataInputStream dataInputStream, String compiledVersion) throws IOException {
        int constantPoolSize = dataInputStream.readUnsignedShort();
        LOGGER.debug("constantPoolSize: " + constantPoolSize);
        ConstantPool constantPool = new ConstantPool();

        // Extract the constants
        for(int i = 1; i < constantPoolSize; i++) {
            Constant constant = this.extractConstant(dataInputStream, i, compiledVersion);
            constant.setConstantPoolIndex(i);
            if(constant.getType().equals("Long") || constant.getType().equals("Double")) {
                i++;
            }
            constantPool.getConstants().add(constant);
        }
        return constantPool;
    }

    /**
     * An auxiliary method to extract the constant from the byte stream. The constant describes the type and contains
     * more information, such as a referenced class or constant value.
     *
     * According to the JVM specification, each item in the constant_pool must begin with a 1-byte tag indicating
     * the kind of cp_info entry. The contents of the info array vary with the value of tag. The valid tags and
     * their values are (example is fetched from JVM 8 specification):
     *
     * - Class              7
     * - FieldRef           9
     * - MethodRef          10
     * - InterfaceMethodred 11
     * - String             8
     * - Integer            3
     * - Float              4
     * - Long               5
     * - Double             6
     * - NameAndType        12
     * - Utf8               1
     * - MethodHandle       15
     * - MethodType         16
     * - InvokeDynamic      18
     *
     * Each tag byte must be followed by two or more bytes giving information about the specific constant. The format
     * of the additional information varies with the tag value.
     *
     * @param dataInputStream
     *  The byte stream associated with the constant extraction.
     * @param constantPoolIndex
     *  The passed constant pool index, for logging and tracking purposes.
     * @param compiledVersion
     *  The compiled version associated with the resource (aka .class file).
     * @return
     *  The extracted constant.
     * @throws IOException
     *  When reading bytes from the stream fails.
     */
    protected Constant extractConstant(DataInputStream dataInputStream, int constantPoolIndex, String compiledVersion) throws IOException {
        // Read tag
        int tag = dataInputStream.readUnsignedByte();
        // Find associated constant pool constant
        ConstantPoolConstant constantPoolConstant = this.findConstantPoolConstantByValue(tag, compiledVersion);
        // Instantiate constant
        Constant constant = new Constant();
        constant.setConstantPoolIndex(constantPoolIndex);
        constant.setTag(tag);
        constant.setType(constantPoolConstant.getType());
        LOGGER.debug("Constant index: " + constant.getConstantPoolIndex() + ", tag: " + constant.getTag() + ", type: " + constant.getType());
        // Extract details
        this.extractConstantDetails(dataInputStream, constant, constantPoolConstant);
        return constant;
    }

    /**
     * An auxiliary method to extract the constant details from the byte stream.
     *
     * @param dataInputStream
     *  The byte stream associated with the constant extraction.
     * @param constant
     *  The constant associated with the details extracted.
     * @param constantPoolConstant
     *  The structure of the constant pool constant.
     * @throws IOException
     *  When reading bytes from the stream fails.
     */
    protected void extractConstantDetails(DataInputStream dataInputStream, Constant constant, ConstantPoolConstant constantPoolConstant) throws IOException {
        for(ConstantPoolInfoFragment infoFragment : constantPoolConstant.getFragments()) {
            ConstantInfo constantInfo = new ConstantInfo();
            constantInfo.setDescription(infoFragment.getDescription());
            this.readInfoSize(dataInputStream, constantInfo, infoFragment);
            constant.getConstantInfoList().add(constantInfo);
        }
    }

    /**
     * Auxiliary method to read a number of bytes, based on the constant pool info data (represented by the
     * ConstantPoolInfoFragment). This instance of reference is based on the specification, and tells how much
     * byes (as in value) must be read. The data being read is set on the ConstantInfo object for further processing.
     *
     * @param dataInputStream
     *  The byte stream associated with the extraction (reading of bytes).
     * @param constantInfo
     *  The constant info associated with the resource.
     * @param infoFragment
     *  The constant pool info fragment associated with the constant pool constant.
     * @throws IOException
     *  When reading bytes from the stream fails.
     */
    protected void readInfoSize(DataInputStream dataInputStream, ConstantInfo constantInfo, ConstantPoolInfoFragment infoFragment) throws IOException {
        switch(infoFragment.getSize()) {
            case "readUnsignedByte" :
                constantInfo.setIntValue(dataInputStream.readUnsignedByte());
                LOGGER.debug("Info fragment size: " + infoFragment.getSize() + ", description: " + infoFragment.getDescription() + ", value: " + constantInfo.getIntValue());
                break;
            case "readInt" :
                constantInfo.setIntValue(dataInputStream.readInt());
                LOGGER.debug("Info fragment size: " + infoFragment.getSize() + ", description: " + infoFragment.getDescription() + ", value: " + constantInfo.getIntValue());
                break;
            case "readFloat" :
                constantInfo.setFloatValue(dataInputStream.readFloat());
                LOGGER.debug("Info fragment size: " + infoFragment.getSize() + ", description: " + infoFragment.getDescription() + ", value: " + constantInfo.getFloatValue());
                break;
            case "readLong" :
                constantInfo.setLongValue(dataInputStream.readLong());
                LOGGER.debug("Info fragment size: " + infoFragment.getSize() + ", description: " + infoFragment.getDescription() + ", value: " + constantInfo.getLongValue());
                break;
            case "readDouble" :
                constantInfo.setDoubeValue(dataInputStream.readDouble());
                LOGGER.debug("Info fragment size: " + infoFragment.getSize() + ", description: " + infoFragment.getDescription() + ", value: " + constantInfo.getDoubeValue());
                break;
            case "readUTF":
                constantInfo.setStringValue(dataInputStream.readUTF());
                LOGGER.debug("Info fragment size: " + infoFragment.getSize() + ", description: " + infoFragment.getDescription() + ", value: " + constantInfo.getStringValue());
                break;
            case "readUnsignedShort" :
            default:
                constantInfo.setIntValue(dataInputStream.readUnsignedShort());
                LOGGER.debug("Info fragment size: " + infoFragment.getSize() + ", description: " + infoFragment.getDescription() + ", value: " + constantInfo.getIntValue());
                break;
        }
    }

    /**
     * Auxiliary method to find an instance of the ConstantPoolConstant based on two parameters,
     * namely the tag and the compiled version.
     *
     * @param tag
     *  The tag associated with the constant pool constant.
     * @param compiledVersion
     *  The compiled version, used to lookup for the correct JVM specification.
     * @return
     *  The constant pool constant.
     */
    protected ConstantPoolConstant findConstantPoolConstantByValue(int tag, String compiledVersion) {
        Optional<ConstantPoolConstant> optionalConstantPoolConstant = Optional.ofNullable(null);

        JavaSpecification javaSpecification = this.lookupMap.get(compiledVersion);
        if(javaSpecification != null) {
            ConstantPoolConstants constantPoolConstants = javaSpecification.getConstantPoolConstants();
            if(constantPoolConstants != null) {
                optionalConstantPoolConstant = constantPoolConstants.getConstantPoolConstants().
                    stream().
                    filter( cp -> Integer.valueOf(cp.getTag()) == tag ).
                    findFirst();
            }
        }
        return optionalConstantPoolConstant.get();
    }
}