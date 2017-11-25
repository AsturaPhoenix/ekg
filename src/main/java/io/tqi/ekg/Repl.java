package io.tqi.ekg;

import static org.petitparser.parser.primitive.CharacterParser.anyOf;
import static org.petitparser.parser.primitive.CharacterParser.digit;
import static org.petitparser.parser.primitive.CharacterParser.noneOf;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.primitive.CharacterParser;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import io.tqi.ekg.KnowledgeBase.Common;
import lombok.Getter;
import lombok.Value;

public class Repl {
    @Value
    private static class Identifier {
        String value;

        @Override
        public String toString() {
            return value;
        }
    }

    @Value
    private static class Argument {
        Identifier parameter;
        Object value;
    }

    //@formatter:off
    private static final Parser
            RAW_IDENTIFIER = CharacterParser
                    .of(Character::isJavaIdentifierStart, "identifier expected").seq(
                            CharacterParser.of(Character::isJavaIdentifierPart,
                                    "identifier expected").star())
                    .flatten(),
            IDENTIFIER = RAW_IDENTIFIER.seq(CharacterParser.of('.').seq(RAW_IDENTIFIER).star())
                    .flatten().map(Identifier::new).trim(),
            ESCAPE = anyOf("\\\""),
            STRING = CharacterParser.of('"')
                    .seq(noneOf("\\\"").or(CharacterParser.of('\\').seq(ESCAPE).pick(1)).star()
                            .map((List<Character> chars) -> chars.stream().map(Object::toString)
                                    .collect(Collectors.joining())),
                            CharacterParser.of('"'))
                    .pick(1).trim(),
            LEADING_DIGIT = CharacterParser.range('1', '9'),
            INTEGER = digit().or(LEADING_DIGIT.seq(digit().plus())),
            NUMBER = INTEGER.flatten().map((String s) -> Integer.parseInt(s)).or(
                    INTEGER.seq(CharacterParser.of('.'), digit().plus()).map(Double::parseDouble)).trim(),
            NODE = IDENTIFIER.or(STRING, NUMBER),
            ALIAS = IDENTIFIER.seq(CharacterParser.of('='), NODE).map((List<Object> value) ->
                    new AbstractMap.SimpleEntry<>(value.get(0), value.get(2))),
            COMMAND_PARSER = NODE.or(ALIAS).end();
    //@formatter:on

    @Getter
    private final KnowledgeBase kb;

    private final Subject<String> commandOutput = PublishSubject.create();

    public Repl(final KnowledgeBase kb) {
        this.kb = kb;
    }

    public Observable<String> commandOutput() {
        return commandOutput;
    }

    public Observable<String> rxOutput() {
        return kb.rxOutput();
    }

    private Node resolveNode(final Object token) {
        if (token instanceof Identifier) {
            return kb.node(((Identifier) token).getValue());
        } else if (token instanceof String) {
            return kb.valueNode((String) token);
        } else if (token instanceof Number) {
            return kb.valueNode((Number) token);
        } else {
            throw new IllegalArgumentException("Unable to resolve a node for token type " + token.getClass());
        }
    }

    public void execute(final String command) {
        final Result result = COMMAND_PARSER.parse(command);

        if (result.isFailure()) {
            commandOutput.onNext(String.format("Invalid command string \"%s\"", command));
        } else if (result.get() instanceof Map.Entry) {
            final Map.Entry<?, ?> alias = (Map.Entry<?, ?>) result.get();
            kb.context().put(resolveNode(alias.getKey()), resolveNode(alias.getValue()));
        } else {
            resolveNode(result.get()).activate();
        }
    }

    public void sendInput(final String input) {
        kb.context().put(kb.node(Common.value), kb.valueNode(input));
        kb.node("Repl.input").activate();
    }
}
