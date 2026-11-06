package io.aria.conductor.execution.credential;

import io.aria.conductor.common.model.PackCredential;
import io.aria.conductor.common.repository.PackCredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves pack credentials with precedence: per-agent override -> pack-global -> host env fallback.
 * Never logs or exposes raw credential values.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackCredentialService {

    private final PackCredentialRepository credentialRepo;
    private final PackCredentialCipher cipher;

    /**
     * Resolve a credential value for a pack+key, with per-agent override and env fallback.
     * Returns null if not found anywhere.
     */
    public String resolve(String packId, String agentId, String credKey) {
        // 1. Per-agent override
        if (agentId != null) {
            Optional<PackCredential> agentCred = credentialRepo.findByPackIdAndAgentIdAndCredKey(packId, agentId, credKey);
            if (agentCred.isPresent()) {
                return cipher.decrypt(agentCred.get().getEncValue());
            }
        }
        // 2. Pack-global
        Optional<PackCredential> globalCred = credentialRepo.findByPackIdAndAgentIdIsNullAndCredKey(packId, credKey);
        if (globalCred.isPresent()) {
            return cipher.decrypt(globalCred.get().getEncValue());
        }
        // 3. Host env fallback (e.g. GITHUB_TOKEN from docker-compose)
        String envValue = System.getenv(credKey);
        if (envValue != null && !envValue.isBlank()) {
            log.debug("Credential {} resolved from host env for pack {}", credKey, packId);
            return envValue;
        }
        return null;
    }

    /**
     * Store or update a credential (encrypted at rest).
     */
    public void store(String packId, String agentId, String credKey, String plaintextValue) {
        Optional<PackCredential> existing = agentId != null
                ? credentialRepo.findByPackIdAndAgentIdAndCredKey(packId, agentId, credKey)
                : credentialRepo.findByPackIdAndAgentIdIsNullAndCredKey(packId, credKey);

        PackCredential cred = existing.orElseGet(() -> PackCredential.builder()
                .id(java.util.UUID.randomUUID().toString())
                .packId(packId)
                .agentId(agentId)
                .credKey(credKey)
                .build());
        cred.setEncValue(cipher.encrypt(plaintextValue));
        credentialRepo.save(cred);
        log.info("Stored credential {} for pack {} (agent={})", credKey, packId, agentId != null ? agentId : "global");
    }
}
