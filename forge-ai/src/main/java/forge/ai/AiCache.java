package forge.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Thread-safe AI cache that isolates data per thread using ThreadLocal.
 * This implementation uses standard Java collections to avoid Guava-specific
 * ConcurrentModificationException issues with WrappedIterators.
 */
public class AiCache {

    // Simple Map of Lists, isolated per thread.
    private static final ThreadLocal<Map<String, List<List<Object>>>> dataMap = 
            ThreadLocal.withInitial(HashMap::new);

    public static boolean identity(Object a, Object b) {
        return a == b;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getCached(String key, Supplier<T> func, List<BiFunction<Object, Object, Boolean>> argsCheck, Object... args) {
        Map<String, List<List<Object>>> map = dataMap.get();
        List<List<Object>> cachedEntries = map.get(key);
        
        if (cachedEntries != null) {
            // Defensive copy of the list of cached results for this key 
            // to prevent CME during iteration if func.get() modifies the map.
            for (List<Object> cached : new ArrayList<>(cachedEntries)) {
                if (cached.size() == args.length + 1) {
                    boolean hit = true;
                    for (int i = 0; i < args.length; i++) {
                        BiFunction<Object, Object, Boolean> checker = argsCheck == null ? Object::equals : argsCheck.get(i);
                        if (!checker.apply(args[i], cached.get(i + 1))) {
                            hit = false;
                            break;
                        }
                    }
                    if (hit) {
                        return (T) cached.get(0);
                    }
                }
            }
        }
        
        T result = func.get();
        List<Object> entry = new ArrayList<>();
        entry.add(result);
        entry.addAll(Arrays.asList(args));
        
        // Re-fetch the list as func.get() might have initialized it.
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        
        return result;
    }

    /**
     * Clears the cache for the current thread.
     */
    public static void clear() {
        dataMap.get().clear();
    }
}
