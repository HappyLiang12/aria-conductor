package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.RuntimeCredential;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence round-trip tests for {@link RuntimeCredentialRepository}. The entity stores
 * ciphertext only, so the {@code enc_*} values below are synthetic stand-ins written by the
 * service layer — never real tokens.
 */
@DataJpaTest
class RuntimeCredentialRepositoryTest {

    @Autowired
    RuntimeCredentialRepository repository;

    private RuntimeCredential credential(String providerId, String encPat) {
        return RuntimeCredential.builder()
                .id(UUID.randomUUID().toString())
                .providerId(providerId)
                .encPat(encPat)
                .build();
    }

    @Test
    void save_thenFindByProviderId_returnsStoredRow() {
        RuntimeCredential saved = repository.save(credential("qoder", "enc-synthetic-1"));

        RuntimeCredential found = repository.findByProviderId("qoder").orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getProviderId()).isEqualTo("qoder");
        assertThat(found.getEncPat()).isEqualTo("enc-synthetic-1");
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void findByProviderId_absent_returnsEmpty() {
        assertThat(repository.findByProviderId("qoder")).isEmpty();
    }

    @Test
    void updatingRow_keepsSameId_andMovesUpdatedAt() {
        RuntimeCredential saved = repository.saveAndFlush(credential("qoder", "enc-old"));
        // Backdate the timestamp so the @PreUpdate callback is the only thing that can move it
        // forward — an entity without the callback would leave the stale value in place.
        Instant staleMark = saved.getUpdatedAt().minusSeconds(3600);
        saved.setUpdatedAt(staleMark);
        saved.setEncPat("enc-new");

        repository.saveAndFlush(saved);

        RuntimeCredential reloaded = repository.findByProviderId("qoder").orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.getEncPat()).isEqualTo("enc-new");
        assertThat(reloaded.getUpdatedAt()).isAfter(staleMark);
    }

    @Test
    void twoProviders_areIndependent() {
        repository.save(credential("qoder", "enc-qoder"));
        repository.save(credential("other-provider", "enc-other"));

        assertThat(repository.findByProviderId("qoder").orElseThrow().getEncPat()).isEqualTo("enc-qoder");
        assertThat(repository.findByProviderId("other-provider").orElseThrow().getEncPat()).isEqualTo("enc-other");
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void delete_removesRow() {
        RuntimeCredential saved = repository.save(credential("qoder", "enc-synthetic-1"));

        repository.deleteById(saved.getId());

        assertThat(repository.findByProviderId("qoder")).isEmpty();
        assertThat(repository.findById(saved.getId())).isEmpty();
    }
}
