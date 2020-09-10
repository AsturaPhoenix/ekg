package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

import lombok.Getter;
import lombok.val;

public interface Posterior extends Node {
  ThresholdIntegrator getIntegrator();

  class Trait implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Node owner;
    @Getter
    private transient ThresholdIntegrator integrator;

    public Trait(final Node owner) {
      this.owner = owner;
      init();
    }

    private void init() {
      integrator = new ThresholdIntegrator() {
        @Override
        protected void onThreshold() {
          owner.activate();
        }
      };
    }

    private void readObject(final ObjectInputStream stream) throws IOException, ClassNotFoundException {
      stream.defaultReadObject();
      init();
    }
  }

  default Posterior conjunction(final Prior... priors) {
    final float coefficient = (ThresholdIntegrator.THRESHOLD + Prior.THRESHOLD_MARGIN) / priors.length;
    if (coefficient * (priors.length - 1) >= ThresholdIntegrator.THRESHOLD) {
      throw new IllegalArgumentException(
          "Too many priors to guarantee reliable conjunction. Recommend staging the evaluation into a tree.");
    }

    for (val prior : priors) {
      prior.setCoefficient(this, coefficient);
    }

    return this;
  }

  default Posterior disjunction(final Prior... priors) {
    for (val prior : priors) {
      prior.then(this);
    }

    return this;
  }

  default Posterior inhibitor(final Prior prior) {
    prior.inhibit(this);
    return this;
  }
}