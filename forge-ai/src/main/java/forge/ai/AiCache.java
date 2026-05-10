package forge.ai;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Thread-safe AI cache that isolates data per thread using ThreadLocal.
 * This implementation prevents ConcurrentModificationException by using 
 * per-thread isolation and defensive copying during iteration.
 */
public class AiCache {

    private static final ThreadLocal<Multimap<String, List<Object>>> dataMap = 
            ThreadLocal.withInitial(HashMultimap::create);

    public static boolean identity(Object a, Object b) {
        return a == b;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getCached(String key, Supplier<T> func, List<BiFunction<Object, Object, Boolean>> argsCheck, Object... args) {
        Multimap<String, List<Object>> map = dataMap.get();
        
        // Iterating over a copy prevents ConcurrentModificationException 
        // if recursive calls to getCached modify the map.
        for (List<Object> cached : Lists.newArrayList(map.get(key))) {
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
        
        T result = func.get();
        List<Object> entry = Lists.newArrayList((Object) result);
        entry.addAll(Arrays.asList(args));
        map.put(key, entry);
        return result;
    }

    /**
     * Clears the cache for the current thread.
     */
    public static void clear() {
        dataMap.get().clear();
    }
}
