package habitTracker.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CapabilityDeployServiceTest {

    @Mock UserSyncSettingsRepository syncSettingsRepository;
    @Mock DriveService driveService;

    // Real (not mocked) — cheap, pure, and using the real thing is exactly what proves the
    // service correctly REUSES VaultEncryptionService instead of rolling its own crypto.
    private final VaultEncryptionService encryptionService = new VaultEncryptionService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CapabilityDeployService service() {
        return new CapabilityDeployService(syncSettingsRepository, driveService, encryptionService, objectMapper);
    }

    @Test
    void deployCapability_encryptsAndUploads_toLazilyCreatedFolder() throws Exception {
        String key = encryptionService.generateKey();
        UserSyncSettings settings = UserSyncSettings.builder()
                .userId("alice").driveRefreshToken("rt").driveFolderId("root-1").encryptionKey(key).build();
        when(syncSettingsRepository.findByUserId("alice")).thenReturn(Optional.of(settings));
        when(driveService.getAccessToken("rt")).thenReturn("access-1");
        when(driveService.findOrCreateFolder("access-1", CapabilityDeployService.CAPABILITY_DEPLOY_FOLDER_NAME, "root-1"))
                .thenReturn("deploy-folder-1");
        when(driveService.uploadFile(eq("access-1"), eq("deploy-folder-1"), anyString(), any()))
                .thenReturn("file-1");

        String fileId = service().deployCapability("alice", "cap-x", 1, "print('hi')");

        assertEquals("file-1", fileId);
        assertEquals("deploy-folder-1", settings.getCapabilityDeployFolderId(), "folder id must be cached on the settings row");
        verify(syncSettingsRepository).save(settings);

        ArgumentCaptor<byte[]> wireCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(driveService).uploadFile(eq("access-1"), eq("deploy-folder-1"), anyString(), wireCaptor.capture());
        byte[] plain = encryptionService.decrypt(key, wireCaptor.getValue());
        CapabilityDeployService.CapabilityPayload payload =
                objectMapper.readValue(plain, CapabilityDeployService.CapabilityPayload.class);
        assertEquals("cap-x", payload.capabilityId());
        assertEquals(1, payload.version());
        assertEquals("print('hi')", payload.sourceCode());
    }

    @Test
    void deployCapability_secondCall_reusesCachedFolderId_doesNotRecreateFolder() {
        String key = encryptionService.generateKey();
        UserSyncSettings settings = UserSyncSettings.builder()
                .userId("alice").driveRefreshToken("rt").driveFolderId("root-1")
                .capabilityDeployFolderId("existing-folder").encryptionKey(key).build();
        when(syncSettingsRepository.findByUserId("alice")).thenReturn(Optional.of(settings));
        when(driveService.getAccessToken("rt")).thenReturn("access-1");
        when(driveService.uploadFile(anyString(), anyString(), anyString(), any())).thenReturn("file-2");

        service().deployCapability("alice", "cap-x", 2, "print('v2')");

        verify(driveService, never()).findOrCreateFolder(any(), any(), any());
        verify(syncSettingsRepository, never()).save(any());
        verify(driveService).uploadFile(eq("access-1"), eq("existing-folder"), anyString(), any());
    }

    @Test
    void deployCapability_userWithoutDriveConnected_throws() {
        when(syncSettingsRepository.findByUserId("nobody")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service().deployCapability("nobody", "cap-x", 1, "code"));
        verifyNoInteractions(driveService);
    }
}
