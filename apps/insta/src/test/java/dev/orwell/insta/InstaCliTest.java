package dev.orwell.insta;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The command line itself: what each invocation means, and what a script sees when one is wrong.
 *
 * <p>None of these reach Apify — every case here is settled before a paid run could start, which is
 * the property worth having. A typo in a flag must not cost money.
 */
class InstaCliTest {

    @Test
    void parsesAProfileLookup() {
        InstaCli.Arguments arguments = InstaCli.Arguments.parse(new String[]{"profile", "nasa"});

        assertThat(arguments.command()).isEqualTo(InstaCli.Command.PROFILE);
        assertThat(arguments.username()).isEqualTo("nasa");
        assertThat(arguments.json()).isFalse();
    }

    @Test
    void parsesAListLookupWithEveryOption() {
        InstaCli.Arguments arguments = InstaCli.Arguments.parse(
                new String[]{"followers", "nasa", "--limit", "250", "--json"});

        assertThat(arguments.command()).isEqualTo(InstaCli.Command.FOLLOWERS);
        assertThat(arguments.limit()).isEqualTo(250);
        assertThat(arguments.json()).isTrue();
        assertThat(arguments.all()).isFalse();
    }

    @Test
    void parsesAResumedWalk() {
        InstaCli.Arguments arguments = InstaCli.Arguments.parse(
                new String[]{"following", "nasa", "--cursor", "abc123"});

        assertThat(arguments.command()).isEqualTo(InstaCli.Command.FOLLOWING);
        assertThat(arguments.cursor()).isEqualTo("abc123");
    }

    /** Honouring both would silently ignore one of them, so it is refused instead. */
    @Test
    void refusesToCombineAllWithACursor() {
        assertThatThrownBy(() -> InstaCli.Arguments.parse(
                new String[]{"followers", "nasa", "--all", "--cursor", "abc"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--all and --cursor");
    }

    /** Silently ignoring a paging flag on a one-result command would be a confusing lie. */
    @Test
    void refusesPagingOptionsOnProfile() {
        assertThatThrownBy(() -> InstaCli.Arguments.parse(
                new String[]{"profile", "nasa", "--limit", "50"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile");
    }

    @Test
    void rejectsUnknownCommandsOptionsAndLimits() {
        assertThatThrownBy(() -> InstaCli.Arguments.parse(new String[]{"stalk", "nasa"}))
                .hasMessageContaining("Unknown command");
        assertThatThrownBy(() -> InstaCli.Arguments.parse(new String[]{"profile"}))
                .hasMessageContaining("Missing username");
        assertThatThrownBy(() ->
                InstaCli.Arguments.parse(new String[]{"followers", "nasa", "--sideways"}))
                .hasMessageContaining("Unknown option");
        assertThatThrownBy(() ->
                InstaCli.Arguments.parse(new String[]{"followers", "nasa", "--limit", "lots"}))
                .hasMessageContaining("must be a number");
        assertThatThrownBy(() ->
                InstaCli.Arguments.parse(new String[]{"followers", "nasa", "--limit", "0"}))
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() ->
                InstaCli.Arguments.parse(new String[]{"followers", "nasa", "--limit"}))
                .hasMessageContaining("needs a value");
    }

    @Test
    void printsUsageOnStdoutAndSucceedsWhenAskedForHelp() {
        Streams streams = new Streams();

        int code = InstaCli.run(new String[]{"--help"}, streams.out(), streams.err());

        assertThat(code).isEqualTo(InstaCli.OK);
        assertThat(streams.outText()).contains("usage: insta");
    }

    /** Usage errors go to stderr, so a caller piping stdout gets nothing rather than garbage. */
    @Test
    void reportsABadInvocationOnStderrWithAUsageExitCode() {
        Streams streams = new Streams();

        int code = InstaCli.run(new String[]{"stalk", "nasa"}, streams.out(), streams.err());

        assertThat(code).isEqualTo(InstaCli.BAD_USAGE);
        assertThat(streams.outText()).isEmpty();
        assertThat(streams.errText()).contains("Unknown command").contains("usage: insta");
    }

    /** Every failure a script might branch on needs its own code, so none may collide. */
    @Test
    void givesEveryOutcomeItsOwnExitCode() {
        assertThat(List.of(InstaCli.OK, InstaCli.UNEXPECTED, InstaCli.BAD_USAGE, InstaCli.NOT_FOUND,
                        InstaCli.UPSTREAM_FAILED, InstaCli.OUT_OF_CREDIT, InstaCli.TIMED_OUT))
                .doesNotHaveDuplicates();
    }

    private record Streams(ByteArrayOutputStream outBytes, ByteArrayOutputStream errBytes) {
        Streams() {
            this(new ByteArrayOutputStream(), new ByteArrayOutputStream());
        }

        PrintStream out() {
            return new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        }

        PrintStream err() {
            return new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        }

        String outText() {
            return outBytes.toString(StandardCharsets.UTF_8);
        }

        String errText() {
            return errBytes.toString(StandardCharsets.UTF_8);
        }
    }
}
