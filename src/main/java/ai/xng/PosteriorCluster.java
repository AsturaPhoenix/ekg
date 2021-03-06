package ai.xng;

import lombok.Getter;

public abstract class PosteriorCluster<T extends Posterior> extends Cluster<T> {
  public static final float DEFAULT_PLASTICITY = .1f;

  @Getter
  private float plasticity = DEFAULT_PLASTICITY;

  public void setPlasticity(final float plasticity) {
    if (plasticity < 0 || plasticity > 1) {
      throw new IllegalArgumentException(String.format("Plasticity (%s) must be [0, 1].", plasticity));
    }
    this.plasticity = plasticity;
  }
}
