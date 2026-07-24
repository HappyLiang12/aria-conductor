package io.aria.conductor.agent.service;

import io.aria.conductor.agent.dto.LlmProviderRequest;
import io.aria.conductor.agent.dto.LlmProviderResponse;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.LlmProvider;
import io.aria.conductor.common.model.LlmProviderType;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmProviderServiceTest {

    @Mock LlmProviderRepository providerRepository;
    @InjectMocks LlmProviderService service;

    private void stubSaveReturnsArgument() {
        when(providerRepository.save(any(LlmProvider.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------------
    // create
    // ---------------------------------------------------------------------

    @Test
    void create_persistsInactiveProviderWithMaskedKey() {
        stubSaveReturnsArgument();
        LlmProviderRequest request = LlmProviderRequest.builder()
                .name("prod-openai").type(LlmProviderType.OPENAI)
                .baseUrl("https://api.openai.com/v1").apiKey("sk-supersecret1234")
                .defaultModel("gpt-4o").maxTokens(2048).build();

        LlmProviderResponse response = service.create(request);

        assertThat(response.getName()).isEqualTo("prod-openai");
        assertThat(response.isActive()).isFalse();
        assertThat(response.getDefaultMaxTokens()).isEqualTo(2048);
        // masking: never round-trip the raw key in clear text.
        assertThat(response.getApiKeyMasked()).isEqualTo("****1234");
        assertThat(response.getApiKeyMasked()).doesNotContain("supersecret");

        ArgumentCaptor<LlmProvider> saved = ArgumentCaptor.forClass(LlmProvider.class);
        verify(providerRepository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isFalse();
        // The persisted entity keeps the real key; only the response is masked.
        assertThat(saved.getValue().getApiKey()).isEqualTo("sk-supersecret1234");
    }

    @Test
    void create_defaultsMaxTokensWhenAbsent() {
        stubSaveReturnsArgument();
        LlmProviderRequest request = LlmProviderRequest.builder()
                .name("p").type(LlmProviderType.LOCAL).apiKey("abcdef").maxTokens(null).build();

        LlmProviderResponse response = service.create(request);

        assertThat(response.getDefaultMaxTokens()).isEqualTo(4096);
    }

    @ParameterizedTest
    @CsvSource({
            "sk-1234567890,****7890",
            "abcd,****",
            "abc,****",
            "'',****"
    })
    void create_masksApiKeyToLastFourOrStars(String rawKey, String expectedMask) {
        stubSaveReturnsArgument();
        LlmProviderResponse response = service.create(LlmProviderRequest.builder()
                .name("p").type(LlmProviderType.OPENAI).apiKey(rawKey).build());

        assertThat(response.getApiKeyMasked()).isEqualTo(expectedMask);
    }

    @Test
    void create_nullApiKey_masksToStars() {
        stubSaveReturnsArgument();
        LlmProviderResponse response = service.create(LlmProviderRequest.builder()
                .name("p").type(LlmProviderType.OPENAI).apiKey(null).build());

        assertThat(response.getApiKeyMasked()).isEqualTo("****");
    }

    // ---------------------------------------------------------------------
    // read
    // ---------------------------------------------------------------------

    @Test
    void listAll_masksEveryProvidersKey() {
        when(providerRepository.findAll()).thenReturn(List.of(
                TestDataBuilder.anLlmProvider().withName("a").withApiKey("sk-aaaa1111").build(),
                TestDataBuilder.anLlmProvider().withName("b").withApiKey("sk-bbbb2222").build()));

        List<LlmProviderResponse> result = service.listAll();

        assertThat(result).extracting(LlmProviderResponse::getApiKeyMasked)
                .containsExactly("****1111", "****2222");
    }

    @Test
    void getById_returnsMaskedProvider() {
        UUID id = UUID.randomUUID();
        when(providerRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.anLlmProvider().withId(id).withApiKey("sk-zzzz9999").build()));

        LlmProviderResponse response = service.getById(id);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getApiKeyMasked()).isEqualTo("****9999");
    }

    @Test
    void getById_throwsNotFoundWhenMissing() {
        UUID id = UUID.randomUUID();
        when(providerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("LlmProvider");
    }

    // ---------------------------------------------------------------------
    // update
    // ---------------------------------------------------------------------

    @Test
    void update_appliesOnlyNonNullFields() {
        UUID id = UUID.randomUUID();
        LlmProvider existing = TestDataBuilder.anLlmProvider().withId(id)
                .withName("old").withBaseUrl("http://old").withApiKey("sk-old0000")
                .withDefaultModel("old-model").withDefaultMaxTokens(1000).build();
        when(providerRepository.findById(id)).thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        LlmProviderRequest request = LlmProviderRequest.builder()
                .name("new-name").defaultModel("new-model").build(); // others null

        LlmProviderResponse response = service.update(id, request);

        assertThat(response.getName()).isEqualTo("new-name");
        assertThat(response.getDefaultModel()).isEqualTo("new-model");
        assertThat(response.getBaseUrl()).isEqualTo("http://old");        // untouched
        assertThat(response.getDefaultMaxTokens()).isEqualTo(1000);        // untouched
    }

    @Test
    void update_replacesApiKeyAndRemasks() {
        UUID id = UUID.randomUUID();
        LlmProvider existing = TestDataBuilder.anLlmProvider().withId(id).withApiKey("sk-old0000").build();
        when(providerRepository.findById(id)).thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        LlmProviderResponse response = service.update(id,
                LlmProviderRequest.builder().apiKey("sk-new5555").build());

        assertThat(response.getApiKeyMasked()).isEqualTo("****5555");
        assertThat(existing.getApiKey()).isEqualTo("sk-new5555");
    }

    @Test
    void update_throwsNotFoundWhenMissing() {
        UUID id = UUID.randomUUID();
        when(providerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, LlmProviderRequest.builder().name("x").build()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(providerRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------
    // delete
    // ---------------------------------------------------------------------

    @Test
    void delete_removesExistingProvider() {
        UUID id = UUID.randomUUID();
        LlmProvider existing = TestDataBuilder.anLlmProvider().withId(id).build();
        when(providerRepository.findById(id)).thenReturn(Optional.of(existing));

        service.delete(id);

        verify(providerRepository).delete(existing);
    }

    @Test
    void delete_throwsNotFoundWhenMissing() {
        UUID id = UUID.randomUUID();
        when(providerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(providerRepository, never()).delete(any());
    }

    // ---------------------------------------------------------------------
    // activation toggling
    // ---------------------------------------------------------------------

    @Test
    void activate_alreadyActive_isNoOpAndDoesNotTouchOtherProviders() {
        UUID id = UUID.randomUUID();
        LlmProvider active = TestDataBuilder.anLlmProvider().withId(id).withActive(true).build();
        when(providerRepository.findById(id)).thenReturn(Optional.of(active));

        LlmProviderResponse response = service.activate(id);

        assertThat(response.isActive()).isTrue();
        verify(providerRepository, never()).save(any());
        verify(providerRepository, never()).findByActiveTrue();
    }

    @Test
    void activate_deactivatesPreviouslyActiveProvider() {
        UUID targetId = UUID.randomUUID();
        UUID currentId = UUID.randomUUID();
        LlmProvider target = TestDataBuilder.anLlmProvider().withId(targetId).withActive(false).build();
        LlmProvider current = TestDataBuilder.anLlmProvider().withId(currentId).withActive(true).build();
        when(providerRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(providerRepository.findByActiveTrue()).thenReturn(Optional.of(current));
        stubSaveReturnsArgument();

        LlmProviderResponse response = service.activate(targetId);

        assertThat(response.isActive()).isTrue();
        assertThat(current.isActive()).isFalse();
        // both the deactivated previous and the newly activated provider are persisted.
        verify(providerRepository).save(current);
        verify(providerRepository).save(target);
    }

    @Test
    void activate_whenNoOtherActive_justActivatesTarget() {
        UUID targetId = UUID.randomUUID();
        LlmProvider target = TestDataBuilder.anLlmProvider().withId(targetId).withActive(false).build();
        when(providerRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(providerRepository.findByActiveTrue()).thenReturn(Optional.empty());
        stubSaveReturnsArgument();

        LlmProviderResponse response = service.activate(targetId);

        assertThat(response.isActive()).isTrue();
        assertThat(target.isActive()).isTrue();
        verify(providerRepository).save(target);
    }

    @Test
    void activate_throwsNotFoundWhenMissing() {
        UUID id = UUID.randomUUID();
        when(providerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activate(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------------
    // getActiveProvider
    // ---------------------------------------------------------------------

    @Test
    void getActiveProvider_returnsActiveEntityUnmasked() {
        LlmProvider active = TestDataBuilder.anLlmProvider().withActive(true).build();
        when(providerRepository.findByActiveTrue()).thenReturn(Optional.of(active));

        assertThat(service.getActiveProvider()).isSameAs(active);
    }

    @Test
    void getActiveProvider_returnsNullWhenNoneActive() {
        when(providerRepository.findByActiveTrue()).thenReturn(Optional.empty());

        assertThat(service.getActiveProvider()).isNull();
    }

    // ---------------------------------------------------------------------
    // testConnection (no real network: an unusable base URL fails fast -> false)
    // ---------------------------------------------------------------------

    @Test
    void testConnection_invalidBaseUrl_returnsFalseWithoutThrowing() {
        UUID id = UUID.randomUUID();
        LlmProvider provider = TestDataBuilder.anLlmProvider().withId(id)
                .withBaseUrl("http://invalid host with spaces").build();
        when(providerRepository.findById(id)).thenReturn(Optional.of(provider));

        assertThat(service.testConnection(id)).isFalse();
    }

    @Test
    void testConnection_throwsNotFoundWhenMissing() {
        UUID id = UUID.randomUUID();
        when(providerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.testConnection(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
