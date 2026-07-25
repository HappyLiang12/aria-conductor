package io.aria.conductor.common.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for the {@link HarnessProfile} record's real logic:
 * {@link HarnessProfile#defaults()}, case-insensitive/null-safe {@link HarnessProfile#denies},
 * {@link HarnessProfile#canEscalateTier} null paths and {@link HarnessProfile#effectiveOutputCap}.
 */
class HarnessProfileTest {

    private HarnessProfile profileWithDenylist(List<String> denylist) {
        return new HarnessProfile("p", denylist,
                new HarnessProfile.Steering(false),
                new HarnessProfile.SelfVerify(true, List.of(), 200, null),
                0, 16_000);
    }

    private HarnessProfile profileWithEscalateTiers(List<String> tiers) {
        return new HarnessProfile("p", List.of(),
                new HarnessProfile.Steering(false),
                new HarnessProfile.SelfVerify(true, tiers, 200, null),
                0, 16_000);
    }

    @Test
    void defaults_isNoOpHistoricalBehaviour() {
        HarnessProfile defaults = HarnessProfile.defaults();

        assertThat(defaults.name()).isEqualTo("default");
        assertThat(defaults.toolDenylist()).isEmpty();
        assertThat(defaults.steering().shellExecToGitPack()).isFalse();
        assertThat(defaults.selfVerify().enabled()).isTrue();
        assertThat(defaults.selfVerify().escalateTiers()).isEmpty();
        assertThat(defaults.selfVerify().maxResponseTokens()).isEqualTo(200);
        assertThat(defaults.selfVerify().promptOverride()).isNull();
        assertThat(defaults.maxToolCallRounds()).isZero();
        assertThat(defaults.maxToolOutputChars()).isEqualTo(16_000);
    }

    @Test
    void defaults_deniesNothing() {
        assertThat(HarnessProfile.defaults().denies("shell_exec")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "shell_exec,true",
            "SHELL_EXEC,true",
            "Shell_Exec,true",
            "git_pack,false",
            "'',false"
    })
    void denies_isCaseInsensitive(String query, boolean expected) {
        HarnessProfile profile = profileWithDenylist(List.of("shell_exec", "http_request"));
        assertThat(profile.denies(query)).isEqualTo(expected);
    }

    @Test
    void denies_nullToolName_returnsFalse() {
        assertThat(profileWithDenylist(List.of("shell_exec")).denies(null)).isFalse();
    }

    @Test
    void denies_nullDenylist_returnsFalse() {
        assertThat(profileWithDenylist(null).denies("shell_exec")).isFalse();
    }

    @Test
    void canEscalateTier_matchesConfiguredTier() {
        HarnessProfile profile = profileWithEscalateTiers(List.of("TIER_2", "TIER_3"));
        assertThat(profile.canEscalateTier("TIER_2")).isTrue();
        assertThat(profile.canEscalateTier("TIER_3")).isTrue();
        assertThat(profile.canEscalateTier("TIER_1")).isFalse();
    }

    @Test
    void canEscalateTier_nullTierName_returnsFalse() {
        HarnessProfile profile = profileWithEscalateTiers(List.of("TIER_2"));
        assertThat(profile.canEscalateTier(null)).isFalse();
    }

    @Test
    void canEscalateTier_nullSelfVerify_returnsFalse() {
        HarnessProfile profile = new HarnessProfile("p", List.of(),
                new HarnessProfile.Steering(false), null, 0, 16_000);
        assertThat(profile.canEscalateTier("TIER_2")).isFalse();
    }

    @Test
    void canEscalateTier_emptyEscalateTiers_returnsFalse() {
        assertThat(profileWithEscalateTiers(List.of()).canEscalateTier("TIER_2")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "8000,999,8000",   // configured cap wins over fallback
            "0,4096,4096",     // zero cap falls back
            "-5,4096,4096"     // negative cap falls back
    })
    void effectiveOutputCap_fallsBackWhenNotPositive(int configured, int fallback, int expected) {
        HarnessProfile profile = new HarnessProfile("p", List.of(),
                new HarnessProfile.Steering(false),
                new HarnessProfile.SelfVerify(true, List.of(), 200, null),
                0, configured);
        assertThat(profile.effectiveOutputCap(fallback)).isEqualTo(expected);
    }

    @Test
    void denies_ignoresUnrelatedCasingOfNonMatch() {
        HarnessProfile profile = profileWithDenylist(Arrays.asList("http_request"));
        assertThat(profile.denies("shell_exec")).isFalse();
    }
}
