package com.ledgercore;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HTTP-level helpers (MockMvc runs the full Spring Security filter chain). */
public abstract class AbstractApiIT extends AbstractIT {
    protected static final String PASSWORD = "Passw0rd-Test1";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;

    protected String body(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    protected String newEmail() {
        return "api" + UUID.randomUUID() + "@test.io";
    }

    protected ResultActions postJson(String url, String token, Object payload) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(body(payload));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    protected ResultActions postWithKey(String url, String token, String key, Object payload) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(body(payload)).header("Idempotency-Key", key);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    protected ResultActions getJson(String url, String token) throws Exception {
        var req = get(url);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    protected ResultActions patchJson(String url, String token, Object payload) throws Exception {
        return mvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(body(payload)).header("Authorization", "Bearer " + token));
    }

    protected void register(String email) throws Exception {
        postJson("/api/v1/auth/register", null,
                Map.of("email", email, "password", PASSWORD, "fullName", "Api Tester")).andExpect(status().isCreated());
    }

    protected JsonNode login(String email, String password) throws Exception {
        return read(postJson("/api/v1/auth/login", null, Map.of("email", email, "password", password)).andExpect(status().isOk()));
    }

    protected String registerAndLogin(String email) throws Exception {
        register(email);
        return login(email, PASSWORD).get("accessToken").asText();
    }

    protected String adminToken() throws Exception {
        return login("admin@ledgercore.test", "Admin-Passw0rd-Test1").get("accessToken").asText();
    }

    /** Registers a user, has the admin grant {@code role}, and logs in again so the token carries the new role. */
    protected String userWithRole(String role) throws Exception {
        String email = newEmail();
        register(email);
        UUID id = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        patchJson("/api/v1/admin/users/" + id + "/roles", adminToken(), Map.of("roles", new String[] {role})).andExpect(status().isOk());
        return login(email, PASSWORD).get("accessToken").asText();
    }

    protected JsonNode openAccount(String token, String currency) throws Exception {
        return read(postJson("/api/v1/accounts", token, Map.of("currency", currency)).andExpect(status().isCreated()));
    }
}
