/**
 * 
 */
package com.github.phantomthief.model.builder.impl;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.lang3.builder.ToStringBuilder.reflectionToString;
import static org.apache.commons.lang3.builder.ToStringStyle.SHORT_PREFIX_STYLE;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.ClassUtils;

import com.github.phantomthief.model.builder.ModelBuilder;
import com.github.phantomthief.model.builder.context.BuildContext;
import com.github.phantomthief.model.builder.context.impl.SimpleBuildContext;
import com.github.phantomthief.model.builder.util.MergeUtils;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;

/**
 * @author w.vela
 */
@SuppressWarnings("unchecked")
public class SimpleModelBuilder<B extends BuildContext> implements ModelBuilder<B> {

    private static org.slf4j.Logger logger = getLogger(SimpleModelBuilder.class);

    // obj.class=>obj->(namespace,ids)
    private final SetMultimap<Class<?>, Function<Object, KeyPair<Set<Object>>>> idExtractors = HashMultimap
            .create();
    // obj.class=>obj->(namespace,values)
    private final SetMultimap<Class<?>, Function<Object, KeyPair<Map<Object, Object>>>> valueExtractors = HashMultimap
            .create();
    // idNamespace=>(valueNamespace, ids->values)
    private final SetMultimap<Object, KeyPair<BiFunction<B, Collection<Object>, Map<Object, Object>>>> valueBuilders = HashMultimap
            .create();
    // targetNamespace=>Function<BuildContext, Object>
    private final Map<Object, Function<BuildContext, Object>> lazyBuilders = new HashMap<>();

    private final ConcurrentMap<Class<?>, Set<Function<Object, KeyPair<Set<Object>>>>> cachedIdExtractors = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, Set<Function<Object, KeyPair<Map<Object, Object>>>>> cachedValueExtractors = new ConcurrentHashMap<>();

    @Override
    public void buildMulti(Iterable<?> sources, B buildContext) {
        if (sources == null) {
            return;
        }
        if (buildContext instanceof SimpleBuildContext) {
            SimpleBuildContext simpleBuildContext = (SimpleBuildContext) buildContext;
            lazyBuilders.forEach((targetNamespace, valueHolder) -> simpleBuildContext
                    .setupLazyNodeData(targetNamespace, valueHolder));
        }

        Set<Object> pendingForBuilding = stream(sources).collect(toSet());

        while (!pendingForBuilding.isEmpty()) {
            logger.debug("building source:{}", pendingForBuilding);
            // namespace->ids
            Map<Object, Set<Object>> idsMap = new HashMap<>();
            // namespace->values
            Map<Object, Map<Object, Object>> valuesMap = new HashMap<>();

            for (Object object : pendingForBuilding) {
                extract(object, buildContext, idsMap, valuesMap);
            }

            logger.debug("extracted data, id:{}", idsMap);
            logger.debug("extracted data, values:{}", valuesMap);
            valueBuild(idsMap, valuesMap, buildContext);
            logger.debug("after value build:{}", valuesMap);
            mergeToBuildContext(valuesMap, buildContext);

            pendingForBuilding = valuesMap.values().stream().flatMap(map -> map.values().stream())
                    .collect(toSet());
        }
    }

    private void mergeToBuildContext(Map<Object, Map<Object, Object>> valuesMap, B buildContext) {
        valuesMap.forEach(
                (valueNamespace, values) -> buildContext.getData(valueNamespace).putAll(values));
    }

    private void valueBuild(Map<Object, Set<Object>> idsMap,
            Map<Object, Map<Object, Object>> valuesMap, B buildContext) {
        idsMap.forEach((idNamespace, ids) -> {
            valueBuilders.get(idNamespace).forEach(valueBuilderWrapper -> {
                Object valueNamespace = valueBuilderWrapper.getKey();
                BiFunction<B, Collection<Object>, Map<Object, Object>> valueBuilder = valueBuilderWrapper
                        .getValue();
                Set<Object> needToBuildIds = filterIdSetOnBuild(ids, buildContext, valuesMap,
                        valueNamespace);
                Map<Object, Object> values = valueBuilder.apply(buildContext, needToBuildIds);
                valuesMap.merge(valueNamespace, values, MergeUtils::merge);
            });
        });
    }

    private Set<Object> filterIdSetOnBuild(Set<Object> original, B buildContext,
            Map<Object, Map<Object, Object>> valuesMap, Object valueNamespace) {
        Set<Object> buildContextExistIds = buildContext.getData(valueNamespace).keySet();
        Set<Object> valueMapExistIds = valuesMap
                .computeIfAbsent(valueNamespace, i -> new HashMap<>()).keySet();
        return original.stream() //
                .filter(id -> !buildContextExistIds.contains(id)) //
                .filter(id -> !valueMapExistIds.contains(id)) //
                .collect(toSet());
    }

    // return new found data.
    private void extract(Object obj, B buildContext, Map<Object, Set<Object>> idsMap,
            Map<Object, Map<Object, Object>> valuesMap) {
        if (obj == null) {
            return;
        }
        cachedValueExtractors
                .computeIfAbsent(obj.getClass(),
                        t -> getAllSuperTypes(t).stream()
                                .flatMap(i -> valueExtractors.get(i).stream()).collect(toSet()))
                .forEach(valueExtractor -> {
                    KeyPair<Map<Object, Object>> values = valueExtractor.apply(obj);
                    Map<Object, Object> filtered = filterValueMap(values, buildContext);
                    idsMap.merge(values.getKey(), new HashSet<>(filtered.keySet()),
                            MergeUtils::merge);
                    valuesMap.merge(values.getKey(), filtered, MergeUtils::merge);
                });
        cachedIdExtractors
                .computeIfAbsent(obj.getClass(), t -> getAllSuperTypes(t).stream()
                        .flatMap(i -> idExtractors.get(i).stream()).collect(toSet()))
                .forEach(idExtractor -> {
                    KeyPair<Set<Object>> ids = idExtractor.apply(obj);
                    idsMap.merge(ids.getKey(), filterIdSet(ids, buildContext, valuesMap),
                            MergeUtils::merge);
                });
    }

    private Set<Object> filterIdSet(KeyPair<Set<Object>> keyPair, B buildContext,
            Map<Object, Map<Object, Object>> valuesMap) {
        Set<Object> buildContextExistIds = buildContext.getData(keyPair.getKey()).keySet();
        Set<Object> valueMapExistIds = valuesMap
                .computeIfAbsent(keyPair.getKey(), i -> new HashMap<>()).keySet();
        return keyPair.getValue().stream() //
                .filter(i -> !buildContextExistIds.contains(i)) //
                .filter(i -> !valueMapExistIds.contains(i)) //
                .collect(toSet());
    }

    private Map<Object, Object> filterValueMap(KeyPair<Map<Object, Object>> keyPair,
            B buildContext) {
        Map<Object, Object> buildContextData = buildContext.getData(keyPair.getKey());
        return keyPair.getValue().entrySet().stream() //
                .filter(e -> !buildContextData.containsKey(e.getKey()))
                .collect(toMap(Entry::getKey, Entry::getValue));
    }

    private static final class KeyPair<V> implements Entry<Object, V> {

        private final Object key;
        private final V value;

        /**
         * @param key
         * @param value
         */
        private KeyPair(Object key, V value) {
            this.key = key;
            this.value = value;
        }

        public Object getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        public V setValue(V value) {
            throw new UnsupportedOperationException();
        }
    }

    public final class OnBuilder<E> {

        private final Class<E> objType;

        private OnBuilder(Class<E> objType) {
            this.objType = objType;
        }

        public ExtractingId id(Function<E, Object> idExtractor) {
            return new ExtractingId(idExtractor);
        }

        public ExtractingValue value(Function<E, Object> valueExtractor) {
            return new ExtractingValue(valueExtractor);
        }

        public <V> ExtractingValue value(Function<E, Iterable<V>> valueExtractor,
                Function<V, Object> idExtractor) {
            return new ExtractingValue(valueExtractor).id(idExtractor);
        }

        public final class ExtractingValue {

            private final Function<E, Object> valueExtractor;
            private Function<Object, Object> idExtractor;

            private ExtractingValue(Function<E, ?> valueExtractor) {
                this.valueExtractor = (Function<E, Object>) valueExtractor;
            }

            public <K> ExtractingValue id(Function<K, Object> idExtractor) {
                this.idExtractor = (Function<Object, Object>) idExtractor;
                return this;
            }

            public SimpleModelBuilder<B> to(Object valueNamespace) {
                valueExtractors.put(objType, obj -> {
                    Object rawValue = valueExtractor.apply((E) obj);
                    Map<Object, Object> value;
                    if (rawValue == null) {
                        value = Collections.emptyMap();
                    } else {
                        if (idExtractor != null) {
                            if (rawValue instanceof Iterable) {
                                value = stream((Iterable<E>) rawValue)
                                        .collect(toMap(idExtractor::apply, identity()));
                            } else {
                                value = Collections.singletonMap(idExtractor.apply(rawValue),
                                        rawValue);
                            }
                        } else {
                            if (rawValue instanceof Map) {
                                value = (Map<Object, Object>) rawValue;
                            } else {
                                logger.warn("invalid value extractor for:{}->{}", obj, rawValue);
                                value = Collections.emptyMap();
                            }
                        }
                    }
                    return new KeyPair<>(valueNamespace, value);
                });
                cachedValueExtractors.clear();
                return SimpleModelBuilder.this;
            }
        }

        public final class ExtractingId {

            private final Function<E, Object> idExtractor;

            private ExtractingId(Function<E, Object> idExtractor) {
                this.idExtractor = idExtractor;
            }

            public SimpleModelBuilder<B> to(Object idNamespace) {
                idExtractors.put(objType, obj -> {
                    Object rawId = idExtractor.apply((E) obj);
                    Set<Object> ids;
                    if (rawId == null) {
                        ids = Collections.emptySet();
                    } else {
                        if (rawId instanceof Iterable) {
                            ids = stream((Iterable<Object>) rawId).collect(toSet());
                        } else {
                            ids = Collections.singleton(rawId);
                        }
                    }
                    return new KeyPair<Set<Object>>(idNamespace, ids);
                });
                cachedIdExtractors.clear();
                return SimpleModelBuilder.this;
            }
        }
    }

    public final class BuildingBuilder {

        private final Object idNamespace;

        /**
         * @param idNamespace
         */
        private BuildingBuilder(Object idNamespace) {
            this.idNamespace = idNamespace;
        }

        @SuppressWarnings("rawtypes")
        public <K> BuildingValue<K> by(Function<Collection<K>, Map<K, ?>> valueBuilder) {
            return new BuildingValue<K>((c, ids) -> (Map) valueBuilder.apply(ids));
        }

        @SuppressWarnings("rawtypes")
        public <K> BuildingValue<K> by(BiFunction<B, Collection<K>, Map<K, ?>> valueBuilder) {
            return new BuildingValue<K>((BiFunction) valueBuilder);
        }

        public final class BuildingValue<K> {

            private final BiFunction<B, Collection<K>, Map<K, Object>> valueBuilderFunction;

            private BuildingValue(
                    BiFunction<B, Collection<K>, Map<K, Object>> valueBuilderFunction) {
                this.valueBuilderFunction = valueBuilderFunction;
            }

            @SuppressWarnings("rawtypes")
            public SimpleModelBuilder<B> to(Object valueNamespace) {
                valueBuilders.put(idNamespace, new KeyPair(valueNamespace, valueBuilderFunction));
                return SimpleModelBuilder.this;
            }
        }
    }

    public <E> OnBuilder<E> on(Class<E> type) {
        return new OnBuilder<>(type);
    }

    public <E> SimpleModelBuilder<B> self(Class<E> type, Function<E, Object> idExtractor) {
        SimpleModelBuilder<B>.OnBuilder<E> onBuilder = new OnBuilder<>(type);
        return onBuilder.new ExtractingValue(i -> i).id((Function<Object, Object>) idExtractor)
                .to(type);
    }

    public BuildingBuilder build(Object idNamespace) {
        return new BuildingBuilder(idNamespace);
    }

    public <K> SimpleModelBuilder<B> build(Object idNamespace,
            BiFunction<B, Collection<K>, Map<K, ?>> valueBuilder) {
        return build(idNamespace).by(valueBuilder).to(idNamespace);
    }

    public <K> SimpleModelBuilder<B> build(Object idNamespace,
            Function<Collection<K>, Map<K, ?>> valueBuilder) {
        return build(idNamespace).by(valueBuilder).to(idNamespace);
    }

    // builder.onLazy(UserCache.class).fromId(ids->dao.build(ids)).to("test");

    public LazyBuilder onLazy(Object sourceNamespace) {
        return new LazyBuilder(sourceNamespace);
    }

    public final class LazyBuilder {

        private final Object sourceNamespace;

        private LazyBuilder(Object sourceNamespace) {
            this.sourceNamespace = sourceNamespace;
        }

        public <E> LazyFromIdBuilder<E> fromId(BiFunction<B, Collection<E>, Object> builder) {
            return new LazyFromIdBuilder<>(builder);
        }

        public <E> LazyFromIdBuilder<E> fromId(Function<Collection<E>, Object> builder) {
            return fromId((buildContext, ids) -> builder.apply(ids));
        }

        public final class LazyFromIdBuilder<E> {

            private final BiFunction<B, Collection<E>, Object> builder;

            private LazyFromIdBuilder(BiFunction<B, Collection<E>, Object> builder) {
                this.builder = builder;
            }

            public SimpleModelBuilder<B> to(Object targetNamespace) {
                lazyBuilders.put(targetNamespace, buildContext -> builder.apply((B) buildContext,
                        (Collection<E>) buildContext.getData(sourceNamespace).keySet()));
                return SimpleModelBuilder.this;
            }
        }
    }

    private Set<Class<?>> getAllSuperTypes(Class<?> iface) {
        Set<Class<?>> classes = new HashSet<>();
        classes.add(iface);
        classes.addAll(ClassUtils.getAllInterfaces(iface));
        classes.addAll(ClassUtils.getAllSuperclasses(iface));
        return classes;
    }

    private <T> Stream<T> stream(Iterable<T> iterable) {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterable.iterator(),
                        (Spliterator.NONNULL | Spliterator.IMMUTABLE | Spliterator.ORDERED)),
                false);
    }

    @Override
    public String toString() {
        return reflectionToString(this, SHORT_PREFIX_STYLE);
    }
}
