package ai.xng;

import java.io.Serializable;

import lombok.Getter;
import lombok.val;

public interface Prior extends Node {
  float THRESHOLD_MARGIN = .2f;
  float DEFAULT_COEFFICIENT = ThresholdIntegrator.THRESHOLD + THRESHOLD_MARGIN;

  Connections.Posteriors getPosteriors();

  class Trait implements Serializable {
    @Getter
    private final Connections.Posteriors posteriors;

    public Trait(final Prior owner) {
      posteriors = new Connections.Posteriors(owner);
    }

    public void activate() {
      final long now = Scheduler.global.now();

      val it = posteriors.iterator();
      while (it.hasNext()) {
        val posterior = it.next();
        // LTD due to reverse STDP
        posterior.edge().distribution
            .reinforce(-posterior.node().getTrace().evaluate(now, posterior.edge().profile)
                * posterior.node().getCluster().getPlasticity());

        if (posterior.edge().distribution.getWeight() == 0) {
          it.remove();
        } else {
          posterior.edge().activate();
        }
      }
    }
  }

  default <T extends Posterior> T then(final T posterior) {
    getPosteriors().getEdge(posterior, IntegrationProfile.TRANSIENT).distribution.set(DEFAULT_COEFFICIENT);
    return posterior;
  }

  default void then(final Posterior... posteriors) {
    for (val posterior : posteriors) {
      then(posterior);
    }
  }

  default void inhibit(final Posterior posterior) {
    getPosteriors().getEdge(posterior, IntegrationProfile.TRANSIENT).distribution.set(-1);
  }
}
