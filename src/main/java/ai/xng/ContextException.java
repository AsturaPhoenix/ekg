package ai.xng;

import com.google.common.collect.ImmutableMap;

/**
 * Exception thrown when a call has been made under a context missing a required
 * key.
 */
public class ContextException extends IllegalArgumentException {
  private static final long serialVersionUID = 6398651175390307056L;

  public final ImmutableMap<Node, Node> index;
  public final Node missingKey;

  public ContextException(final Context context, final Node missingKey) {
    super("Context missing required key " + missingKey);
    this.index = ImmutableMap.copyOf(context.index);
    this.missingKey = missingKey;
  }
}