package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.function.BiConsumer;

import com.google.common.collect.ImmutableList;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.val;

public abstract class Cluster<T extends Node> implements Serializable {
  private transient RecencyQueue<WeakReference<T>> activations;
  private transient Subject<T> rxActivations;

  public Observable<T> rxActivations() {
    return rxActivations;
  }

  public Cluster() {
    init();
  }

  // TODO: register cleanup task

  private void writeObject(final ObjectOutputStream o) throws IOException {
    o.defaultWriteObject();
    val nodes = new ArrayList<T>();
    for (val ref : activations) {
      final T node = ref.get();
      if (node != null) {
        nodes.add(node);
      }
    }
    o.writeObject(nodes);
  }

  private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
    o.defaultReadObject();
    init();
    // Nodes will be re-added as they activate.
    o.readObject();
  }

  private void init() {
    activations = new RecencyQueue<>();
    rxActivations = PublishSubject.create();
  }

  protected class Link implements Serializable {
    private transient RecencyQueue<WeakReference<T>>.Link link;

    public Link(final T node) {
      link = activations.new Link(new WeakReference<>(node));
    }

    private void writeObject(final ObjectOutputStream o) throws IOException {
      o.defaultWriteObject();
      o.writeObject(link.get().get());
    }

    private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
      o.defaultReadObject();
      link = activations.new Link(new WeakReference<>((T) o.readObject()));
    }

    /**
     * Promotes the node within the recency queue. This should be called at the
     * beginning of the node activation to ensure that any side effects that query
     * the recency queue receive a sequence consistent with the updated activation
     * timestamps.
     */
    public void promote() {
      link.promote();
      rxActivations.onNext(link.get().get());
    }
  }

  public void clean() {
    // TODO: Guard against connected but rarely activated nodes blocking GC by
    // thresholding this.
    val it = activations.reverseIterator();
    while (it.hasNext() && it.next().get() == null) {
      it.remove();
    }
  }

  public Iterable<T> activations() {
    return () -> new Iterator<T>() {
      final Iterator<WeakReference<T>> it = activations.iterator();
      T next;

      {
        advance();
      }

      private void advance() {
        while (it.hasNext()) {
          next = it.next()
              .get();
          if (next == null) {
            it.remove();
          } else {
            return;
          }
        }

        next = null;
      }

      @Override
      public boolean hasNext() {
        return next != null;
      }

      @Override
      public T next() {
        val next = this.next;
        advance();
        return next;
      }
    };
  }

  public static <T extends Node> void forEachByTrace(final Cluster<T> cluster, final IntegrationProfile profile,
      final long t, final BiConsumer<T, Float> action) {
    final long horizon = t - profile.period();

    for (final T node : cluster.activations()) {
      if (node.getLastActivation().get() <= horizon) {
        break;
      }

      final float trace = node.getTrace().evaluate(t, profile);
      if (trace > 0) {
        action.accept(node, trace);
      }
    }
  }

  public static record PriorClusterProfile(Cluster<? extends Prior> cluster,
      ImmutableList<IntegrationProfile> profiles) {

    public PriorClusterProfile(final Cluster<? extends Prior> cluster, final IntegrationProfile... profiles) {
      this(cluster, ImmutableList.copyOf(profiles));
    }

    public static class ListBuilder {
      private final ImmutableList.Builder<PriorClusterProfile> backing = ImmutableList.builder();
      private ImmutableList<IntegrationProfile> baseProfiles = ImmutableList.of(IntegrationProfile.TRANSIENT);

      public ListBuilder baseProfiles(final IntegrationProfile... profiles) {
        baseProfiles = ImmutableList.copyOf(profiles);
        return this;
      }

      public ListBuilder add(final Cluster<? extends Prior> cluster, final IntegrationProfile... additionalProfiles) {
        backing.add(
            new PriorClusterProfile(cluster,
                ImmutableList.<IntegrationProfile>builder()
                    .addAll(baseProfiles)
                    .addAll(Arrays.asList(additionalProfiles))
                    .build()));
        return this;
      }

      public ImmutableList<PriorClusterProfile> build() {
        return backing.build();
      }
    }
  }

  private static void associate(final Iterable<PriorClusterProfile> priors, final Posterior posterior,
      final long t, final float weight) {
    val conjunction = new ConjunctionJunction();
    for (val prior : priors) {
      for (val profile : prior.profiles) {
        forEachByTrace(prior.cluster, profile, t, (node, trace) -> conjunction.add(node, profile, trace));
      }
    }
    conjunction.build(posterior, weight);
  }

  /**
   * Forms explicit conjunctive associations between the given prior cluster and
   * posterior cluster, using the given integration profile for the new edges. The
   * active posteriors considered at the time this function executes are always
   * based on a {@link IntegrationProfile#TRANSIENT} window.
   */
  public static void associate(final Iterable<PriorClusterProfile> priors,
      final Cluster<? extends Posterior> posteriorCluster, final long effectiveTime) {
    forEachByTrace(posteriorCluster, IntegrationProfile.TRANSIENT, effectiveTime,
        (posterior, posteriorTrace) -> {
          associate(priors, posterior, posterior.getLastActivation().get(), posteriorTrace);
        });
  }

  public static void associate(final Iterable<PriorClusterProfile> priors,
      final Cluster<? extends Posterior> posteriorCluster) {
    associate(priors, posteriorCluster, Scheduler.global.now());
  }

  public static void associate(final Cluster<? extends Prior> priorCluster,
      final Cluster<? extends Posterior> posteriorCluster) {
    associate(Arrays.asList(new PriorClusterProfile(priorCluster, IntegrationProfile.TRANSIENT)), posteriorCluster);
  }

  private static float weightByTrace(final float value, final float identity, final float trace) {
    final float tolerantTrace = Math.min(1, trace * (1 + Prior.THRESHOLD_MARGIN));
    return identity + (value - identity) * tolerantTrace;
  }

  /**
   * Breaks associations between the given prior cluster and posterior cluster.
   * The active posteriors considered at the time this function executes are
   * always based on a {@link IntegrationProfile#TRANSIENT} window. The degree to
   * which associations are broken are determined by this window and the prior
   * trace.
   */
  public static void disassociate(final Cluster<? extends Prior> priorCluster,
      final Cluster<? extends Posterior> posteriorCluster) {
    forEachByTrace(posteriorCluster, IntegrationProfile.TRANSIENT, Scheduler.global.now(),
        (posterior, posteriorTrace) -> {
          // For each posterior, find all priors in the designated cluster and reduce
          // their weight by the product of the pertinent traces.

          for (val prior : posterior.getPriors()) {
            if (prior.node().getCluster() == priorCluster) {
              final float priorTrace = prior.node().getTrace()
                  .evaluate(posterior.getLastActivation().get(), prior.edge().profile);
              if (priorTrace > 0) {
                prior.edge().distribution.reinforce(weightByTrace(
                    -prior.edge().distribution.getWeight(), 0, priorTrace * posteriorTrace));
              }
            }
          }
        });
  }

  public static void disassociateAll(final Cluster<? extends Prior> priorCluster) {
    // This is a reinforcement rather than a simple clear to smooth by trace.
    forEachByTrace(priorCluster, IntegrationProfile.TRANSIENT, Scheduler.global.now(),
        (prior, trace) -> {
          for (val entry : prior.getPosteriors()) {
            entry.edge().distribution
                .reinforce(weightByTrace(-entry.edge().distribution.getWeight(), 0, trace));
          }
        });
  }

  public static void scalePosteriors(final Cluster<? extends Prior> priorCluster, final float factor) {
    forEachByTrace(priorCluster, IntegrationProfile.TRANSIENT, Scheduler.global.now(),
        (prior, trace) -> {
          for (val entry : prior.getPosteriors()) {
            entry.edge().distribution.scale(weightByTrace(factor, 1, trace));
          }
        });
  }
}
