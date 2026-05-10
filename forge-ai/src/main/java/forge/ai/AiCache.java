package forge.ai;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Global AI cache to share calculations within a single decision cycle.
 * Uses ThreadLocal to ensure thread-safety during concurrent match simulations.
 */
public class AiCache {

    // stores result + args as vector
    // Each thread gets its own cache, isolating concurrent matches/evaluations.
    private static final ThreadLocal<Multimap<String, List<Object>>> dataMap = 
            ThreadLocal.withInitial(HashMultimap::create);

    public static boolean identity(Object a, Object b) {
        return a == b;
    }

    // the cache is shared within a thread for calculations that can be reused
    @SuppressWarnings("unchecked")
    public static <T> T getCached(String key, Supplier<T> func, List<BiFunction<Object, Object, Boolean>> argsCheck, Object... args) {
        Multimap<String, List<Object>> map = dataMap.get();
        Collection<List<Object>> cachedEntries = map.get(key);
        
        // Iteration is safe because 'map' is local to the current thread.
        for (List<Object> cached : cachedEntries) {
            boolean hit = true;
            if (cached.size() != args.length + 1) {
                hit = false;
            } else {
                for (int i = 0; i < args.length; i++) {
                    BiFunction<Object, Object, Boolean> checker = argsCheck == null ? Object::equals : argsCheck.get(i);
                    if (!checker.apply(args[i], cached.get(i + 1))) {
                        hit = false;
                        break;
                    }
                }
            }
            if (hit) {
                return (T) cached.get(0);
            }
        }
        
        T result = func.get();
        List<Object> cached = Lists.newArrayList((Object) result);
        cached.addAll(Arrays.asList(args));
        map.put(key, cached);
        return result;
    }

    /**
     * Clears the cache for the current thread.
     */
    public static void clear() {
        dataMap.get().clear();
    }
}
