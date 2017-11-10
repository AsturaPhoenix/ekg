package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.MapMaker;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import javafx.geometry.Point3D;
import lombok.RequiredArgsConstructor;

public class KnowledgeBase implements Serializable, AutoCloseable, Iterable<Node> {
    private static final long serialVersionUID = 4850129606513054849L;

    @RequiredArgsConstructor
    private static class IdentityKey implements Serializable {
        private static final long serialVersionUID = -2428169144581856842L;

        final Serializable value;

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof IdentityKey && value == ((IdentityKey) obj).value;
        }
    }

    private final ConcurrentMap<Serializable, Node> index = new ConcurrentHashMap<>();
    private final Map<IdentityKey, Node> valueIndex = new MapMaker().weakValues().makeMap();

    // Nodes not otherwise referenced should be garbage collected, so this
    // collection holds weak references.
    private transient NodeQueue nodes = new NodeQueue();
    private transient NodePhysics physics;

    private transient Subject<String> rxOutput;
    private transient Subject<Node> rxNodeAdded;
    private transient Subject<Object> rxChange;

    public final Node EXECUTE = node("execute"), ARGUMENT = node("arg"), CALLBACK = node("callback"),
            CLASS = node("class"), OBJECT = node("object"), PROPERTY = node("property"), METHOD = node("method"),
            EXCEPTION = node("exception"), SOURCE = node("source"), DESTINATION = node("destination"),
            VALUE = node("value"), COEFFICIENT = node("coefficient");

    public enum BuiltIn {
        clearProperties {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) {
                node.clearProperties();
                return node;
            }
        },
        copyProperty {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) {
                final Node source = node.getProperty(kb.SOURCE), dest = node.getProperty(kb.DESTINATION);
                final Node sourceProp = source.getProperty(kb.PROPERTY), destProp = dest.getProperty(kb.PROPERTY);
                Preconditions.checkNotNull(sourceProp);
                Preconditions.checkNotNull(destProp);
                final Node value = source.getProperty(kb.OBJECT).getProperty(sourceProp);
                dest.getProperty(kb.OBJECT).setProperty(destProp, value);
                return value;
            }
        },
        /**
         * Takes two args: {@link KnowledgeBase#OBJECT} and "property".
         */
        getProperty {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) {
                final Node object = node.getProperty(kb.OBJECT), property = node.getProperty(kb.PROPERTY);
                return object.getProperty(property);
            }
        },
        /**
         * A limited Java interop bridge. Method parameter types must be fully
         * and exactly specified via "paramN" properties on the argument node,
         * having values of Java classes. Arguments are passed as "argN"
         * properties on the argument node, and must be serializable. Missing
         * arguments are passed null.
         */
        invoke {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) throws Exception {
                final Node classNode = node.getProperty(kb.CLASS), objectNode = node.getProperty(kb.OBJECT),
                        methodNode = node.getProperty(kb.METHOD);
                final Object object;
                final Class<?> clazz;

                if (objectNode != null) {
                    object = objectNode.getValue();

                    if (classNode != null) {
                        clazz = (Class<?>) classNode.getValue();
                        if (!clazz.isAssignableFrom(object.getClass())) {
                            throw new IllegalArgumentException("Provided class does not match object class");
                        }
                    } else {
                        clazz = object.getClass();
                    }
                } else {
                    object = null;
                    clazz = (Class<?>) classNode.getValue();
                }

                ArrayList<Class<?>> params = new ArrayList<>();
                Node param;
                for (int i = 1; (param = node.getProperty(kb.param(i))) != null; i++) {
                    params.add((Class<?>) param.getValue());
                }

                Method method = clazz.getMethod((String) methodNode.getValue(), params.toArray(new Class<?>[0]));
                ArrayList<Object> args = new ArrayList<>();
                for (int i = 1; i <= params.size(); i++) {
                    Node arg = node.getProperty(kb.arg(i));
                    args.add(arg == null ? null : arg.getValue());
                }

                Object ret = method.invoke(object, args.toArray());
                if (method.getReturnType() != null) {
                    return kb.valueNode((Serializable) ret);
                } else {
                    return null;
                }
            }
        },
        javaClass {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) throws ClassNotFoundException {
                return kb.valueNode(Class.forName((String) node.getValue()));
            }
        },
        node {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) {
                return kb.node();
            }
        },
        print {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) {
                kb.rxOutput.onNext(Objects.toString(node.getValue()));
                return node;
            }
        },
        setCoefficient {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) {
                final Node dest = node.getProperty(kb.DESTINATION);
                dest.getSynapse().setCoefficient(node.getProperty(kb.SOURCE),
                        ((Number) node.getProperty(kb.COEFFICIENT).getValue()).floatValue());
                return dest;
            }
        },
        /**
         * This can also be used to rename default return values, which are
         * normally put to the ARGUMENT property of the callback.
         * <p>
         * Args:
         * <ul>
         * <li>{@link KnowledgeBase#OBJECT}
         * <li>{@link KnowledgeBase#PROPERTY}
         * <li>{@link KnowledgeBase#VALUE}
         * </ul>
         */
        setProperty {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) {
                final Node object = node.getProperty(kb.OBJECT), property = node.getProperty(kb.PROPERTY),
                        value = node.getProperty(kb.VALUE);
                if (property == null) {
                    throw new NullPointerException();
                }
                object.setProperty(property, value);
                return object;
            }
        };

        public abstract Node impl(final KnowledgeBase kb, final Node node) throws Exception;
    }

    public KnowledgeBase() {
        init();
    }

    private void init() {
        rxOutput = PublishSubject.create();
        rxChange = PublishSubject.create();
        physics = new NodePhysics();

        for (final Node node : nodes) {
            initNode(node);
        }

        for (final BuiltIn builtIn : BuiltIn.values()) {
            registerBuiltIn(builtIn);
        }

        final TreeMap<String, Node> sortedIndex = new TreeMap<>();

        for (final Entry<Serializable, Node> entry : index.entrySet()) {
            final String stringKey = entry.getKey() == null ? null : entry.getKey().toString();
            if (Strings.isNullOrEmpty(stringKey))
                continue;

            final Node node = entry.getValue();

            if (node.getComment() == null) {
                node.setComment(stringKey);
            }

            if (node.getLocation() == null) {
                sortedIndex.put(stringKey, node);
            }
        }

        int x = 0;
        for (final Node node : sortedIndex.values()) {
            node.setLocation(new Point3D(x++, 0, 0));
        }

        rxNodeAdded = PublishSubject.create();
    }

    private void initNode(final Node node) {
        // fields may be initialized before kb subjects are, in which case kb
        // init will catch this
        if (rxChange != null) {
            node.rxActivate().subscribe(t -> {
                final Node fn = node.getProperty(EXECUTE);
                if (fn != null) {
                    invoke(fn, node.getProperty(ARGUMENT), node.getProperty(CALLBACK));
                }
            });
            node.rxChange().subscribe(rxChange::onNext);
            physics.add(node);
        }

        if (rxNodeAdded != null) {
            rxNodeAdded.onNext(node);
        }
    }

    public void invoke(final Node fn, final Node arg, final Node callback) {
        fn.setProperty(ARGUMENT, arg);
        fn.setProperty(CALLBACK, callback);
        fn.activate();
    }

    private Node registerBuiltIn(final BuiltIn builtIn) {
        // TODO(rosswang): Keep the original built-in impl node indexed separate
        // from the main index so that we can rebind the impl to the correct
        // node after deserialization.
        final Node node = node(builtIn);
        node.rxActivate().subscribe(t -> {
            final Node result;
            try {
                result = builtIn.impl(KnowledgeBase.this, node.getProperty(ARGUMENT));
            } catch (final Exception e) {
                invoke(EXCEPTION, valueNode(e).setProperty(SOURCE, node), null);
                return;
            }
            final Node callback = node.getProperty(CALLBACK);
            if (callback != null) {
                invoke(callback, result, null);
            }
        });
        node.setRefractory(0);
        return node;
    }

    private void writeObject(final ObjectOutputStream o) throws IOException {
        o.defaultWriteObject();
        final Set<Node> serNodes = new HashSet<>();
        for (final Node node : nodes) {
            serNodes.add(node);
        }
        o.writeObject(serNodes);
    }

    @SuppressWarnings("unchecked")
    private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
        stream.defaultReadObject();

        final Set<Node> serNodes = (Set<Node>) stream.readObject();
        int oldSize = serNodes.size();
        serNodes.addAll(index.values());
        if (serNodes.size() > oldSize) {
            System.out.println(
                    "WARNING: Serialized node set dropped at least " + (serNodes.size() - oldSize) + " nodes.");
        }

        nodes = new NodeQueue();
        nodes.addAll(serNodes);

        init();
    }

    @Override
    public void close() {
        rxOutput.onComplete();
        rxNodeAdded.onComplete();
        rxChange.onComplete();
    }

    public Observable<Node> rxNodeAdded() {
        return rxNodeAdded;
    }

    public Observable<String> rxOutput() {
        return rxOutput;
    }

    public Observable<Object> rxChange() {
        return rxChange;
    }

    /**
     * Gets or creates a node representing a positional argument. These are
     * typically property names under {@code ARGUMENT} nodes.
     * 
     * @param ordinal
     *            one-based argument index
     * @return
     */
    public Node arg(final int ordinal) {
        return node("arg" + ordinal);
    }

    /**
     * Gets or creates a node representing a positional parameter type, for use
     * with {@link BuiltIn#invoke} as property names under {@code ARGUMENT}
     * nodes.
     * 
     * @param ordinal
     *            one-based argument index
     * @return
     */
    public Node param(final int ordinal) {
        return node("param" + ordinal);
    }

    public Node node(final BuiltIn builtIn) {
        return node("BuiltIn." + builtIn.name());
    }

    public Node node(final String identifier) {
        return node(new Identifier(identifier));
    }

    public Node node(final Identifier identifier) {
        return getOrCreateNode(identifier, null);
    }

    public Node valueNode(final Serializable value) {
        return valueIndex.computeIfAbsent(new IdentityKey(value), x -> {
            final Node node = new Node(value);
            initNode(node);
            nodes.add(node);
            return node;
        });
    }

    public Node getNode(final Identifier identifier) {
        return index.get(identifier);
    }

    private Node getOrCreateNode(final Serializable label, final Serializable value) {
        return index.computeIfAbsent(label, x -> {
            final Node node = new Node(value);
            if (label != null)
                node.setComment(label.toString());
            initNode(node);
            nodes.add(node);
            return node;
        });
    }

    // All kb nodes must be created through a node(...) or valueNode(...) method
    // to ensure the proper callbacks are set.
    public Node node() {
        final Node node = new Node();
        initNode(node);
        nodes.add(node);
        return node;
    }

    public void indexNode(final String identifier, final Node node) {
        indexNode(new Identifier(identifier), node);
    }

    public void indexNode(final Identifier identifier, final Node node) {
        index.put(identifier, node);
    }

    @Override
    public Iterator<Node> iterator() {
        return nodes.iterator();
    }
}
