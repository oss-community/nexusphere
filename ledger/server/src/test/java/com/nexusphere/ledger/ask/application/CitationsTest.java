package com.nexusphere.ledger.ask.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CitationsTest {

    @Test
    void sentencesKeepTheirCitationsWhereverTheyStand() {
        Citations.Parsed parsed = Citations.parse(
                "invoice-agent sent 2 emails [#3][#4]. It read a file for 3.5 seconds. Bob was denied once [#7, #9].\n"
                        + "Everything else [#8] was fine.", Set.of(3L, 4L, 7L, 8L, 9L));

        assertThat(parsed.sentences()).containsExactly(
                new Citations.Sentence("invoice-agent sent 2 emails.", List.of(3L, 4L)),
                new Citations.Sentence("Bob was denied once.", List.of(7L, 9L)),
                new Citations.Sentence("Everything else was fine.", List.of(8L)));
        assertThat(parsed.dropped()).isEqualTo(1);
    }

    @Test
    void aSentenceCitingEvidenceThatWasNotGivenIsDropped() {
        Citations.Parsed parsed = Citations.parse("Alice paid twice [#3][#99]. Alice read a file [#3].", Set.of(3L));

        assertThat(parsed.sentences()).extracting(Citations.Sentence::text).containsExactly("Alice read a file.");
        assertThat(parsed.dropped()).isEqualTo(1);
    }

    @Test
    void citationsAfterTheFullStopBelongToTheSentence() {
        assertThat(Citations.split("One. [#1] Two? [#2][#3] Three")).containsExactly("One. [#1]", "Two? [#2][#3]",
                "Three");
    }

    @Test
    void anEmptyAnswerHasNoSentences() {
        assertThat(Citations.parse(null, Set.of()).sentences()).isEmpty();
        assertThat(Citations.parse("  \n ", Set.of()).dropped()).isZero();
    }
}
