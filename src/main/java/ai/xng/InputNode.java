package ai.xng;

import java.util.Optional;

public abstract class InputNode implements Prior {
  private static final long serialVersionUID = 1L;

  private final Node.Trait node = new Node.Trait();
  private final Prior.Trait output = new Prior.Trait(this);

  @Override
  public Integrator getTrace() {
    return node.getTrace();
  }

  @Override
  public Optional<Long> getLastActivation() {
    return node.getLastActivation();
  }

  @Override
  public ConnectionMap.PosteriorMap getPosteriors() {
    return output.getPosteriors();
  }

  @Override
  public void activate() {
    node.activate();
    output.activate();
  }
}
