package habitTracker.sync;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Hand-rolled Drive v3 REST client via RestTemplate — deliberately not the google-api-client
// library, to keep javaapp's footprint inside its 384m container cap (see docker-compose).
@Service
public class DriveService {

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String FILES_URL = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files";

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${google.oauth.client-id:disabled}")
    private String clientId;

    @Value("${google.oauth.client-secret:disabled}")
    private String clientSecret;

    public boolean isConfigured() {
        return isSet(clientId) && isSet(clientSecret);
    }

    private boolean isSet(String v) {
        return v != null && !v.isBlank() && !"disabled".equals(v);
    }

    public String clientId() {
        return clientId;
    }

    public record TokenResponse(String accessToken, String refreshToken, Long expiresInSeconds) {}
    public record DriveFile(String id, String name) {}

    /** Authorization-code exchange, used once at connect time — Google returns a refresh token here. */
    public TokenResponse exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");
        return postToken(form);
    }

    /** Refresh-token grant — used on every subsequent server-side Drive call. */
    public String getAccessToken(String refreshToken) {
        return getAccessTokenWithExpiry(refreshToken).accessToken();
    }

    /**
     * Same refresh-token grant, but keeps expires_in — used to hand the BROWSER a short-lived
     * bridge token (see SyncController.status()) so it can push to Drive directly while the
     * server is unreachable, without ever holding the durable refresh token or client secret.
     */
    public TokenResponse getAccessTokenWithExpiry(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        return postToken(form);
    }

    @SuppressWarnings("unchecked")
    private TokenResponse postToken(MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<Map> resp = restTemplate.postForEntity(TOKEN_URL, new HttpEntity<>(form, headers), Map.class);
        Map<String, Object> body = Objects.requireNonNull(resp.getBody());
        Number expiresIn = (Number) body.get("expires_in");
        return new TokenResponse((String) body.get("access_token"), (String) body.get("refresh_token"),
                expiresIn == null ? null : expiresIn.longValue());
    }

    public String findOrCreateFolder(String accessToken, String name, String parentId) {
        String parentClause = parentId == null ? "'root' in parents" : ("'" + parentId + "' in parents");
        String q = "mimeType='application/vnd.google-apps.folder' and name='" + escape(name) + "' and "
                + parentClause + " and trashed=false";
        List<Map<String, Object>> found = listRaw(accessToken, q);
        if (!found.isEmpty()) {
            return (String) found.get(0).get("id");
        }

        HttpHeaders headers = jsonHeaders(accessToken);
        Map<String, Object> meta = new HashMap<>();
        meta.put("name", name);
        meta.put("mimeType", "application/vnd.google-apps.folder");
        if (parentId != null) meta.put("parents", List.of(parentId));
        ResponseEntity<Map> resp = restTemplate.postForEntity(FILES_URL, new HttpEntity<>(meta, headers), Map.class);
        return (String) Objects.requireNonNull(resp.getBody()).get("id");
    }

    public List<DriveFile> listFiles(String accessToken, String folderId) {
        String q = "'" + folderId + "' in parents and trashed=false";
        List<Map<String, Object>> raw = listRaw(accessToken, q);
        List<DriveFile> out = new ArrayList<>();
        for (Map<String, Object> f : raw) {
            out.add(new DriveFile((String) f.get("id"), (String) f.get("name")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listRaw(String accessToken, String q) {
        String url = FILES_URL + "?q=" + encode(q) + "&fields=" + encode("files(id,name)") + "&spaces=drive";
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders(accessToken));
        ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
        Object files = Objects.requireNonNull(resp.getBody()).get("files");
        return files == null ? List.of() : (List<Map<String, Object>>) files;
    }

    /** Two-step create: metadata-only POST, then a media-only PATCH — avoids hand-rolling multipart/related. */
    public String uploadFile(String accessToken, String folderId, String filename, byte[] bytes) {
        HttpHeaders metaHeaders = jsonHeaders(accessToken);
        Map<String, Object> meta = new HashMap<>();
        meta.put("name", filename);
        meta.put("parents", List.of(folderId));
        ResponseEntity<Map> created = restTemplate.postForEntity(FILES_URL, new HttpEntity<>(meta, metaHeaders), Map.class);
        String fileId = (String) Objects.requireNonNull(created.getBody()).get("id");

        HttpHeaders mediaHeaders = authHeaders(accessToken);
        mediaHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        restTemplate.exchange(UPLOAD_URL + "/" + fileId + "?uploadType=media", HttpMethod.PATCH,
                new HttpEntity<>(bytes, mediaHeaders), Void.class);
        return fileId;
    }

    public byte[] downloadFile(String accessToken, String fileId) {
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders(accessToken));
        ResponseEntity<byte[]> resp = restTemplate.exchange(
                FILES_URL + "/" + fileId + "?alt=media", HttpMethod.GET, entity, byte[].class);
        return resp.getBody();
    }

    public void deleteFile(String accessToken, String fileId) {
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders(accessToken));
        restTemplate.exchange(FILES_URL + "/" + fileId, HttpMethod.DELETE, entity, Void.class);
    }

    private HttpHeaders authHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private HttpHeaders jsonHeaders(String accessToken) {
        HttpHeaders headers = authHeaders(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
