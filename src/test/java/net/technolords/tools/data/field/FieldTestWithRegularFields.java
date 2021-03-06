package net.technolords.tools.data.field;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FieldTestWithRegularFields {
    // Ljava/lang/Boolean;
    // Signature: None...
    static Boolean my_boolean = true;

    // Ljava/util/List;
    // Signature: Ljava/util/List<Ljava/lang/Integer;>;
    volatile List<Integer> numbers = new ArrayList<>();

    // Ljava/util/Map;
    // Signature: Ljava/util/Map<Ljava/lang/String;*>;
    transient Map<String , ?> collection = new HashMap<>();

    // Ljava/util/List;
    // Signature: Ljava/util/List<Ljava/util/List<Ljava/util/Map<**>;>;>;
    List<List<Map<?, ?>>> nestedCollections = new ArrayList<>();

    // Field descriptor: [J
    // Signature: None
    long[] longs = {1L, 2L, 4L};
}
