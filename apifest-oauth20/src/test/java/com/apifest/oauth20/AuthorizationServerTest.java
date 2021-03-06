/*
 * Copyright 2013-2014, ApiFest project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apifest.oauth20;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpStatus;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpHeaders;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.apifest.oauth20.api.UserDetails;
import com.apifest.oauth20.persistence.DBManager;

/**
 * @author Rossitsa Borissova
 */
public class AuthorizationServerTest {

    AuthorizationServer authServer;
    String grantType;
    String clientId = "203598599234220";
    String clientSecret = "105ef93e7bb386da3a23c32e8563434fad005fd0a6a88315fcdf946aa761c838";

    @BeforeMethod
    public void setup() {
        OAuthServer.log = mock(Logger.class);
        try {
            String path = (new File(getClass().getClassLoader().getResource("apifest-oauth-test.properties").toURI())).toString();
            System.setProperty("properties.file", path);
        } catch (URISyntaxException uex) {
            System.err.println(uex.getMessage());
        }
        OAuthServerContext.OAuthServerContextBuilder builder = new OAuthServerContext.OAuthServerContextBuilder();
        OAuthServer.loadConfig(builder);
        OAuthServerContext ctx = builder.build();
        OAuthServer.context = ctx;

        AuthorizationServer.log = mock(Logger.class);
        authServer = spy(new AuthorizationServer(ctx.getUserAuthenticationClass(), ctx.getCustomGrantTypeHandler()));
        grantType = ctx.getCustomGrantType() == null ? "" : ctx.getCustomGrantType();
        authServer.db = mock(DBManager.class);
        authServer.clientCredentialsService = spy(new ClientCredentialsService());
        authServer.clientCredentialsService.db = authServer.db;
        authServer.scopeService = mock(ScopeService.class);
        OAuthException.log = mock(Logger.class);
        ApplicationInfo.log = mock(Logger.class);
    }

    @Test
    public void when_client_id_not_registered_return_error_invalid_client_id() {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);

        // WHEN
        HttpResponseStatus status = null;
        String message = null;
        try {
            authServer.issueAuthorizationCode(req);
        } catch (OAuthException e) {
            status = e.getHttpStatus();
            message = e.getMessage();
        }

        // THEN
        assertEquals(status, HttpResponseStatus.BAD_REQUEST);
        assertEquals(message, new TokenError(TokenErrorTypes.INACTIVE_CLIENT_CREDENTIALS).toString());
    }

    @Test
    public void when_response_type_not_supported_return_error_unsupported_response_type() {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        willReturn("http://localhost/oauth20/authorize?client_id=1232&response_type=no").given(req).getUri();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClientId("1232");

        // WHEN
        HttpResponseStatus status = null;
        String message = null;
        try {
            authServer.issueAuthorizationCode(req);
        } catch (OAuthException e) {
            status = e.getHttpStatus();
            message = e.getMessage();
        }

        // THEN
        assertEquals(status, HttpResponseStatus.BAD_REQUEST);
        assertEquals(message, new TokenError(TokenErrorTypes.UNSUPPORTED_RESPONSE_TYPE).toString());
    }

    @Test
    public void when_redirect_uri_not_valid_return_error_invalid_redirect_uri() {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        willReturn(
                "http://localhost/oauth20/authorize?client_id=1232&response_type=code&redirect_uri=tp%3A%2F%2Fexample.com")
                .given(req).getUri();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClientId("1232");

        // WHEN
        HttpResponseStatus status = null;
        String message = null;
        try {
            authServer.issueAuthorizationCode(req);
        } catch (OAuthException e) {
            status = e.getHttpStatus();
            message = e.getMessage();
        }

        // THEN
        assertEquals(status, HttpResponseStatus.BAD_REQUEST);
        assertEquals(message, new TokenError(TokenErrorTypes.INVALID_REDIRECT_URI).toString());
    }

    @Test
    public void when_valid_token_return_access_token() throws Exception {
        // GIVEN
        String token = "a9855207b560ac824dfb84f4d235243afdccfacaa3a32c66baeeec06eb0afa9c";
        AccessToken accessToken = mock(AccessToken.class);
        given(accessToken.getToken()).willReturn(token);
        given(accessToken.isValid()).willReturn(true);
        given(authServer.db.findAccessToken(accessToken.getToken())).willReturn(accessToken);

        // WHEN
        AccessToken result = authServer.isValidToken(token);

        // THEN
        assertEquals(result, accessToken);
    }

    @Test
    public void when_invalid_token_found_return_null() throws Exception {
        // GIVEN
        String token = "a9855207b560ac824dfb84f4d235243afdccfacaa3a32c66baeeec06eb0afa9c";
        AccessToken accessToken = mock(AccessToken.class);
        willReturn(false).given(accessToken).isValid();
        given(accessToken.getToken()).willReturn(token);
        given(authServer.db.findAccessToken(accessToken.getToken())).willReturn(accessToken);

        // WHEN
        AccessToken result = authServer.isValidToken(token);

        // THEN
        assertNull(result);
    }

    @Test
    public void when_not_valid_token_return_null() throws Exception {
        // GIVEN
        String token = "a9855207b560ac824dfb84f4d235243afdccfacaa3a32c66baeeec06eb0afa9c";

        // WHEN
        AccessToken result = authServer.isValidToken(token);

        // THEN
        assertNull(result);
    }

    @Test
    public void when_valid_client_id_and_active_status_return_true() throws Exception {
        // GIVEN
        willReturn(true).given(authServer.clientCredentialsService).isActiveClientId(clientId);

        // WHEN
        boolean result = authServer.clientCredentialsService.isActiveClientId(clientId);

        // THEN
        assertTrue(result);
    }

    @Test
    public void when_valid_client_id_and_inactive_status_return_true() throws Exception {
        // GIVEN
        ClientCredentials creds = mock(ClientCredentials.class);
        given(creds.getStatus()).willReturn(ClientCredentials.INACTIVE_STATUS);
        given(authServer.clientCredentialsService.db.findClientCredentials(clientId)).willReturn(creds);

        // WHEN
        boolean result = authServer.clientCredentialsService.isActiveClientId(clientId);

        // THEN
        assertFalse(result);
    }

    @Test
    public void when_not_valid_client_id_return_false() throws Exception {
        // GIVEN

        // WHEN
        boolean result = authServer.clientCredentialsService.isActiveClientId(clientId);

        // THEN
        assertFalse(result);
    }

    @Test
    public void when_issue_auth_code_validate_client_id() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        given(authServer.clientCredentialsService.db.findClientCredentials(clientId)).willReturn(
                mock(ClientCredentials.class));
        given(req.getUri())
                .willReturn("http://example.com/oauth20/authorize?client_id=" + clientId);

        // WHEN
        try {
            authServer.issueAuthorizationCode(req);
        } catch (OAuthException e) {
            // nothing to do
        }

        // THEN
        verify(authServer.clientCredentialsService).isActiveClientId(clientId);
    }

    @Test
    public void when_issue_auth_code_invoke_generate_code() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        willReturn(true).given(authServer.clientCredentialsService).isActiveClientId(clientId);
        given(req.getUri())
                .willReturn(
                        "http://example.com/oauth20/authorize?redirect_uri=http%3A%2F%2Fexample.com&response_type=code&client_id="
                                + clientId);
        willReturn("basic").given(authServer.scopeService).getValidScope(null, clientId);

        // WHEN
        authServer.issueAuthorizationCode(req);

        // THEN
        verify(authServer).generateCode();
    }

    @Test
    public void when_issue_token_and_client_id_not_the_same_as_token_return_error()
            throws Exception {
        // GIVEN
        String redirectUri = "example.com";

        String authCode = "eWPoZNvLxVDxuoVBCnGurPXefa#ttxKfryNbLPDvPFsFSkXVhreWW=HvULXWANTnhR=UEtkiaCxsOxgv_nTpqNWQFB-zGkQBHVoqQkjiWkyRuAHZWkFfn#sNeBhJVgOsR=F_vA"
                + "mJwoOh_ooe#ovaJVCOiZls_DzvkhOnRVrlDRSzZrbZIB_rwGXjpoeXdJlIjZQGhSR#";
        given(authServer.db.findAuthCode(authCode, redirectUri)).willReturn(mock(AuthCode.class));

        HttpRequest req = mock(HttpRequest.class);
        String content = "redirect_uri=" + redirectUri
                + "&grant_type=authorization_code&code=eWPoZNvLxVDxuoVBCnGurPXefa#ttxKfryNbLPDvPFsFSkXVhreWW=HvULXWANTnhR=UEtkiaCxsOxgv_nTpq"
                + "NWQFB-zGkQBHVoqQkjiWkyRuAHZWkFfn#sNeBhJVgOsR=F_vAmJwoOh_ooe#ovaJVCOiZls_DzvkhOnRVrlDRSzZrbZIB_rwGXjpoeXdJlIjZQGhSR#";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);
        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);

        // WHEN
        String errorMsg = null;
        try {
            authServer.issueAccessToken(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        verify(authServer).findAuthCode(any(TokenRequest.class));
        assertEquals(errorMsg, new TokenError(TokenErrorTypes.INVALID_CLIENT_CREDENTIALS).toString());
    }

    @Test
    public void when_issue_token_validate_auth_code_and_client_id() throws Exception {
        // GIVEN
        String redirectUri = "example.com";
        willReturn(true).given(authServer.clientCredentialsService).isActiveClientId(clientId);
        String code = "eWPoZNvLxVDxuoVBCnGurPXefa#ttxKfryNbLPDvPFsFSkXVhreWW=HvULXWANTnhR=UEtkiaCxsOxgv_nTpqNWQFB-zGkQBHVoqQkjiWkyRuAHZWkFfn#sNeBhJVgOsR=F_vA"
                + "mJwoOh_ooe#ovaJVCOiZls_DzvkhOnRVrlDRSzZrbZIB_rwGXjpoeXdJlIjZQGhSR#";
        AuthCode authCode = mock(AuthCode.class);
        given(authCode.getClientId()).willReturn(clientId);
        given(authServer.db.findAuthCode(code, redirectUri)).willReturn(authCode);

        HttpRequest req = mock(HttpRequest.class);
        String content = "redirect_uri="
                + redirectUri
                + "&grant_type=authorization_code&code=eWPoZNvLxVDxuoVBCnGurPXefa#ttxKfryNbLPDvPFsFSkXVhreWW=HvULXWANTnhR=UEtkiaCxsOxgv_nTpq"
                + "NWQFB-zGkQBHVoqQkjiWkyRuAHZWkFfn#sNeBhJVgOsR=F_vAmJwoOh_ooe#ovaJVCOiZls_DzvkhOnRVrlDRSzZrbZIB_rwGXjpoeXdJlIjZQGhSR#";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);
        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);

        // WHEN
        authServer.issueAccessToken(req);

        // THEN
        verify(authServer).findAuthCode(any(TokenRequest.class));
    }

    @Test
    public void when_auth_code_not_valid_return_error() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "redirect_uri=example.com"
                + "&grant_type=authorization_code&code=not_valid_code";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);
        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);

        // WHEN
        String errorMsg = null;
        try {
            authServer.issueAccessToken(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        assertEquals(errorMsg, new TokenError(TokenErrorTypes.INVALID_AUTH_CODE).toString());
    }

    @Test
    public void when_auth_code_for_another_redirect_uri_return_token() throws Exception {
        // GIVEN
        String redirectUri1 = "example1.com";
        String redirectUri2 = "example2.com";
        willReturn(true).given(authServer.clientCredentialsService).isActiveClientId(clientId);

        String code = "eWPoZNvLxVDxuoVBCnGurPXefa#ttxKfryNbLPDvPFsFSkXVhreWW=HvULXWANTnhR=UEtkiaCxsOxgv_nTpqNWQFB-zGkQBHVoqQkjiWkyRuAHZWkFfn#sNeBhJVgOsR=F_vA"
                + "mJwoOh_ooe#ovaJVCOiZls_DzvkhOnRVrlDRSzZrbZIB_rwGXjpoeXdJlIjZQGhSR#";
        AuthCode authCode = mock(AuthCode.class);
        given(authCode.getClientId()).willReturn(clientId);
        willReturn(authCode).given(authServer.db).findAuthCode(code, redirectUri1);
        willReturn(authCode).given(authServer.db).findAuthCode(code, redirectUri2);

        HttpRequest req = mock(HttpRequest.class);
        String content = "redirect_uri="
                + redirectUri2
                + "&grant_type=authorization_code&code=eWPoZNvLxVDxuoVBCnGurPXefa#ttxKfryNbLPDvPFsFSkXVhreWW=HvULXWANTnhR=UEtkiaCxsOxgv_nTpq"
                + "NWQFB-zGkQBHVoqQkjiWkyRuAHZWkFfn#sNeBhJVgOsR=F_vAmJwoOh_ooe#ovaJVCOiZls_DzvkhOnRVrlDRSzZrbZIB_rwGXjpoeXdJlIjZQGhSR#";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);
        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);

        // WHEN
        AccessToken token = authServer.issueAccessToken(req);

        // THEN
        verify(authServer.db).findAuthCode(code, redirectUri2);
        assertNotNull(token);
    }

    @Test
    public void when_register_store_appName() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"name\":\"name\",\"scope\":\"basic\",\"redirect_uri\":\"http://example.com\"}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn(Response.APPLICATION_JSON).given(headers).get(HttpHeaders.Names.CONTENT_TYPE);
        willReturn(headers).given(req).headers();
        willDoNothing().given(authServer.clientCredentialsService.db).storeClientCredentials(any(ClientCredentials.class));
        willReturn(mock(Scope.class)).given(authServer.db).findScope("basic");

        // WHEN
        authServer.clientCredentialsService.issueClientCredentials(req);

        // THEN
        verify(authServer.clientCredentialsService.db).storeClientCredentials(any(ClientCredentials.class));
    }

    @Test
    public void when_register_with_non_existing_scope_return_error_message() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"name\":\"name\",\"scope\":\"basic\",\"redirect_uri\":\"http://example.com\"}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn(Response.APPLICATION_JSON).given(headers).get(HttpHeaders.Names.CONTENT_TYPE);
        willReturn(headers).given(req).headers();
        willDoNothing().given(authServer.db).storeClientCredentials(any(ClientCredentials.class));
        willReturn(null).given(authServer.db).findScope("basic");

        // WHEN
        String errorMsg = null;
        try {
            authServer.clientCredentialsService.issueClientCredentials(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        verify(authServer.db, never()).storeClientCredentials(any(ClientCredentials.class));
        assertEquals(errorMsg, ScopeService.SCOPE_DOES_NOT_EXIST_ERROR);
    }

    @Test
    public void when_register_with_several_scopes_check_all_scopes() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"name\":\"name\",\"scope\":\"basic extended\",\"redirect_uri\":\"http://example.com\"}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn(Response.APPLICATION_JSON).given(headers).get(HttpHeaders.Names.CONTENT_TYPE);
        willReturn(headers).given(req).headers();
        willDoNothing().given(authServer.db).storeClientCredentials(any(ClientCredentials.class));
        willReturn(mock(Scope.class)).given(authServer.db).findScope("basic");
        willReturn(mock(Scope.class)).given(authServer.db).findScope("extended");

        // WHEN
        authServer.clientCredentialsService.issueClientCredentials(req);

        // THEN
        verify(authServer.db).findScope("basic");
        verify(authServer.db).findScope("extended");
    }

    @Test
    public void when_no_app_name_passed_return_error() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"scope\":\"basic\",\"redirect_uri\":\"http://example.com\"}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn(Response.APPLICATION_JSON).given(headers).get(HttpHeaders.Names.CONTENT_TYPE);
        willReturn(headers).given(req).headers();

        // WHEN
        String errorMsg = null;
        try {
            authServer.clientCredentialsService.issueClientCredentials(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        assertEquals(errorMsg, ClientCredentialsService.REGISTER_APP_NAME_OR_SCOPE_OR_URI_IS_NULL);
    }

    @Test
    public void when_no_scope_passed_return_error() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"name\":\"name\",\"redirect_uri\":\"http://example.com\"}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn(Response.APPLICATION_JSON).given(headers).get(HttpHeaders.Names.CONTENT_TYPE);
        willReturn(headers).given(req).headers();

        // WHEN
        String errorMsg = null;
        try {
            authServer.clientCredentialsService.issueClientCredentials(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        assertEquals(errorMsg, ClientCredentialsService.REGISTER_APP_NAME_OR_SCOPE_OR_URI_IS_NULL);
    }

    @Test
    public void when_no_redirect_uri_passed_return_error() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"name\":\"name\",\"scope\":\"basic\"}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn(Response.APPLICATION_JSON).given(headers).get(HttpHeaders.Names.CONTENT_TYPE);
        willReturn(headers).given(req).headers();

        // WHEN
        String errorMsg = null;
        try {
            authServer.clientCredentialsService.issueClientCredentials(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        assertEquals(errorMsg, ClientCredentialsService.REGISTER_APP_NAME_OR_SCOPE_OR_URI_IS_NULL);
    }

    @Test
    public void when_invalid_redirect_uri_passed_return_error() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"name\":\"name\",\"scope\":\"basic\",\"redirect_uri\":\"htp://example.com\"}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn(Response.APPLICATION_JSON).given(headers).get(HttpHeaders.Names.CONTENT_TYPE);
        willReturn(headers).given(req).headers();

        // WHEN
        String errorMsg = null;
        try {
            authServer.clientCredentialsService.issueClientCredentials(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        assertEquals(errorMsg, ClientCredentialsService.REGISTER_APP_NAME_OR_SCOPE_OR_URI_IS_NULL);
    }

    @Test
    public void when_client_is_registered_return_app_name() throws Exception {
        // GIVEN
        String clientId = "740503633355700";
        String appName = "TestDemoApp";
        ClientCredentials creds = mock(ClientCredentials.class);
        given(creds.getId()).willReturn(clientId);
        given(creds.getName()).willReturn(appName);
        given(authServer.db.findClientCredentials(clientId)).willReturn(creds);

        // WHEN
        ApplicationInfo result = authServer.getApplicationInfo(clientId);

        // THEN
        assertEquals(result.getName(), appName);
    }

    @Test
    public void when_client_is_not_registered_return_null_app_name() throws Exception {
        // GIVEN
        String clientId = "not_registered_client_id";

        // WHEN
        ApplicationInfo result = authServer.getApplicationInfo(clientId);

        // THEN
        assertNull(result);
    }

    @Test
    public void when_get_clientId_from_Basic_Auth_call_get_Header_method() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn("token").given(headers).get(anyString());
        willReturn(headers).given(req).headers();

        // WHEN
        AuthorizationServer.getBasicAuthorizationClientCredentials(req);

        // THEN
        verify(req.headers()).get(HttpHeaders.Names.AUTHORIZATION);
    }

    @Test
    public void when_Basic_Auth_header_empty_return_null() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn("token").given(headers).get(HttpHeaders.Names.AUTHORIZATION);
        willReturn(headers).given(req).headers();

        // WHEN
        String[] clientCreds = AuthorizationServer.getBasicAuthorizationClientCredentials(req);

        // THEN
        assertNull(clientCreds[0]);
        assertNull(clientCreds[1]);
    }

    @Test
    public void when_Basic_Auth_header_return_clientId() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String clientId = "740503633355700";
        String clientSecret = "7405036333557004234394sadasd214124";
        String basic = clientId + ":" + clientSecret;
        String headerValue = AuthorizationServer.BASIC
                + Base64.encodeBase64String(basic.getBytes());

        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn(headerValue).given(headers).get(HttpHeaders.Names.AUTHORIZATION);
        willReturn(headers).given(req).headers();

        willReturn(true).given(authServer.db).validClient(clientId, clientSecret);

        // WHEN
        String[] result = AuthorizationServer.getBasicAuthorizationClientCredentials(req);

        // THEN
        assertEquals(result [0], clientId);
        assertEquals(result [1], clientSecret);
    }

    @Test
    public void when_client_id_null_throw_exception() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "grant_type=password&username=user&password=pass";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        willReturn(new DefaultHttpHeaders()).given(req).headers();

        // WHEN
        String errorMsg = null;
        try {
            authServer.issueAccessToken(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        TokenError err = new TokenError(TokenErrorTypes.MANDATORY_PARAM_MISSING);
        err.setMessageParams("client_id");
        assertEquals(errorMsg, err.toString());
    }

    @Test
    public void when_issue_token_and_redirect_id_not_the_same_as_auth_code_return_error()
            throws Exception {
        // GIVEN
        String redirectUri = "example.com";
        String redirectUri2 = "example.com2222";
        willReturn(true).given(authServer.clientCredentialsService).isActiveClientId(clientId);

        String authCode = "eWPoZNvLxVDxuoVBCnGurPXefa#ttxKfryNbLPDvPFsFSkXVhreWW=HvULXWANTnhR=UEtkiaCxsOxgv_nTpqNWQFB-zGkQBHVoqQkjiWkyRuAHZWkFfn#sNeBhJVgOsR=F_vA"
                + "mJwoOh_ooe#ovaJVCOiZls_DzvkhOnRVrlDRSzZrbZIB_rwGXjpoeXdJlIjZQGhSR#";
        AuthCode loadedCode = mock(AuthCode.class);
        given(loadedCode.getClientId()).willReturn(clientId);
        given(loadedCode.getRedirectUri()).willReturn(redirectUri);
        given(authServer.db.findAuthCode(authCode, redirectUri2)).willReturn(loadedCode);

        HttpRequest req = mock(HttpRequest.class);
        String content = "redirect_uri="
                + redirectUri2
                + "&grant_type=authorization_code&code=eWPoZNvLxVDxuoVBCnGurPXefa#ttxKfryNbLPDvPFsFSkXVhreWW=HvULXWANTnhR=UEtkiaCxsOxgv_nTpq"
                + "NWQFB-zGkQBHVoqQkjiWkyRuAHZWkFfn#sNeBhJVgOsR=F_vAmJwoOh_ooe#ovaJVCOiZls_DzvkhOnRVrlDRSzZrbZIB_rwGXjpoeXdJlIjZQGhSR#";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);
        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);

        // WHEN
        String errorMsg = null;
        try {
            authServer.issueAccessToken(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        verify(authServer).findAuthCode(any(TokenRequest.class));
        assertEquals(errorMsg, new TokenError(TokenErrorTypes.INVALID_REDIRECT_URI).toString());
    }

    @Test
    public void when_grant_type_refresh_token_update_original_access_token_status()
            throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String refreshToken = "403b510679013ea1813b6fb5f76e7ddfedb8852d9eb8eef73";
        String content = "grant_type=" + TokenRequest.REFRESH_TOKEN + "&refresh_token=" + refreshToken;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        AccessToken accessToken = mock(AccessToken.class);
        willReturn("02d31ca13a0e448802b063ca2e16010b74b0e96ce9e05e953e").given(accessToken).getToken();
        willReturn(refreshToken).given(accessToken).getRefreshToken();
        willReturn(accessToken).given(authServer.db).findAccessTokenByRefreshToken(refreshToken, clientId);
        willDoNothing().given(authServer.db).updateAccessTokenValidStatus(anyString(), anyBoolean());
        willDoNothing().given(authServer.db).storeAccessToken(any(AccessToken.class));

        // WHEN
        AccessToken result = authServer.issueAccessToken(req);

        // THEN
        assertNotNull(result.getRefreshToken());
        verify(authServer.db).updateAccessTokenValidStatus(accessToken.getToken(), false);
    }

    @Test
    public void when_grant_type_client_credentials_issue_access_token_without_refresh_token()
            throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "grant_type=" + TokenRequest.CLIENT_CREDENTIALS + "&scope=basic";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        ClientCredentials clientCredentials = new ClientCredentials();
        clientCredentials.setScope("basic");
        clientCredentials.setId(clientId);
        given(authServer.db.findClientCredentials(clientId)).willReturn(clientCredentials);
        willReturn("basic").given(authServer.scopeService).getValidScopeByScope(anyString(), anyString());
        willDoNothing().given(authServer.db).storeAccessToken(any(AccessToken.class));
        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);

        // WHEN
        AccessToken result = authServer.issueAccessToken(req);

        // THEN
        assert ("".equals(result.getRefreshToken()));
    }

    @Test
    public void when_grant_type_password_issue_access_token_with_refresh_token() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "grant_type=" + TokenRequest.PASSWORD + "&username=rossi&password=test";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        willDoNothing().given(authServer.db).storeAccessToken(any(AccessToken.class));
        UserDetails userDetails = new UserDetails("123456", null);
        willReturn(userDetails).given(authServer).authenticateUser("rossi", "test", req);
        willReturn("basic").given(authServer.scopeService).getValidScope(null, clientId);

        // WHEN
        AccessToken result = authServer.issueAccessToken(req);

        // THEN
        assertNotNull(result.getRefreshToken());
    }

    @Test
    public void when_grant_type_password_and_auth_failed_return_error() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "grant_type=" + TokenRequest.PASSWORD + "&username=rossi&password=test";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(null).given(authServer).authenticateUser("rossi", "test", req);
        willReturn("basic").given(authServer.scopeService).getValidScope(null, clientId);
        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);

        // WHEN
        String errorMsg = null;
        try {
            authServer.issueAccessToken(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        assertEquals(errorMsg, new TokenError(TokenErrorTypes.INVALID_USERNAME_PASSWORD).toString());
    }

    @Test
    public void when_issue_access_token_type_password_get_password_expires_in() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "grant_type=" + TokenRequest.PASSWORD + "&username=rossi&password=test";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        UserDetails userDetails = new UserDetails("3232232122", null);
        willReturn(userDetails).given(authServer).authenticateUser("rossi", "test", req);
        willReturn("basic").given(authServer.scopeService).getValidScope(null, clientId);

        // WHEN
        authServer.issueAccessToken(req);

        // THEN
        verify(authServer).getExpiresIn(TokenRequest.PASSWORD, "basic");
    }

    @Test
    public void when_issue_access_token_type_password_check_valid_client_credentials_and_status() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);

        String clientSecret = "f754cb0cd78c4c36fa3c1c0325ef72bb4a011373";
        String content = "grant_type=" + TokenRequest.PASSWORD + "&username=rossi&password=test&client_id=" + clientId + "&client_secret=" + clientSecret;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        UserDetails userDetails = new UserDetails("3232232122", null);
        willReturn(userDetails).given(authServer).authenticateUser("rossi", "test", req);
        willReturn("basic").given(authServer.scopeService).getValidScope(null, clientId);

        // WHEN
        authServer.issueAccessToken(req);

        // THEN
        verify(authServer).getExpiresIn(TokenRequest.PASSWORD, "basic");
        verify(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
    }

    @Test
    public void when_issue_access_token_type_client_credentials_get_client_credentials_expires_in()
            throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "grant_type=" + TokenRequest.CLIENT_CREDENTIALS;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        ClientCredentials clientCredentials = new ClientCredentials();
        clientCredentials.setScope("basic");
        clientCredentials.setId(clientId);
        given(authServer.db.findClientCredentials(clientId)).willReturn(clientCredentials);
        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        willReturn("basic").given(authServer.scopeService).getValidScopeByScope(null, "basic");
        willReturn(1800).given(authServer.scopeService).getExpiresIn(TokenRequest.CLIENT_CREDENTIALS, "basic");

        // WHEN
        authServer.issueAccessToken(req);

        // THEN
        verify(authServer).getExpiresIn(TokenRequest.CLIENT_CREDENTIALS, "basic");
        assertEquals(authServer.getExpiresIn(TokenRequest.CLIENT_CREDENTIALS, "basic"), "1800");
    }

    @Test
    public void when_refresh_access_token_get_password_expires_in() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String refreshToken = "403b510679013ea1813b6fb5f76e7ddfedb8852d9eb8eef73";
        String content = "grant_type=" + TokenRequest.REFRESH_TOKEN + "&refresh_token="
                + refreshToken;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        AccessToken accessToken = mock(AccessToken.class);
        willReturn("basic").given(accessToken).getScope();
        willReturn("02d31ca13a0e448802b063ca2e16010b74b0e96ce9e05e953e").given(accessToken).getToken();
        willReturn(accessToken).given(authServer.db).findAccessTokenByRefreshToken(refreshToken, clientId);
        willDoNothing().given(authServer.db).updateAccessTokenValidStatus(anyString(), anyBoolean());
        willDoNothing().given(authServer.db).storeAccessToken(any(AccessToken.class));
        willReturn(900).given(authServer.scopeService).getExpiresIn(TokenRequest.PASSWORD, "basic");

        // WHEN
        authServer.issueAccessToken(req);

        // THEN
        verify(authServer).getExpiresIn(TokenRequest.PASSWORD, "basic");
        assertEquals(authServer.getExpiresIn(TokenRequest.PASSWORD, "basic"), "900");
    }

    @Test
    public void when_expired_token_update_valid_to_false() throws Exception {
        // GIVEN
        String token = "a9855207b560ac824dfb84f4d235243afdccfacaa3a32c66baeeec06eb0afa9c";
        AccessToken accessToken = mock(AccessToken.class);
        given(accessToken.getToken()).willReturn(token);
        given(accessToken.isValid()).willReturn(true);
        given(authServer.db.findAccessToken(accessToken.getToken())).willReturn(accessToken);
        given(accessToken.tokenExpired()).willReturn(true);

        // WHEN
        AccessToken result = authServer.isValidToken(token);

        // THEN
        verify(authServer.db).updateAccessTokenValidStatus(accessToken.getToken(), false);
        assertNull(result);
    }

    @Test(expectedExceptions = OAuthException.class)
    public void when_revoke_token_with_client_id_null_will_throw_exception() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"access_token\":\"9376e098e8190835a0b41d83355f92d66f425469\"," +
            "\"client_secret\":\"bb635eb22c5b5ce3de06e31bb88be7ae\"}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();

        // WHEN
        authServer.revokeToken(req);
    }

    @Test
    public void when_revoke_token_get_access_token_null_return_false() throws Exception {
        // GIVEN

        String accessToken = "9376e098e8190835a0b41d83355f92d66f425469";
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"access_token\":" + accessToken + "," +
            "\"client_id\":" + clientId + "}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        willReturn(true).given(authServer.clientCredentialsService).isExistingClient(clientId);

        willReturn(null).given(authServer.db).findAccessToken(accessToken);

        // WHEN
        boolean revoked = authServer.revokeToken(req);

        // THEN
        assertFalse(revoked);
        verify(authServer.clientCredentialsService).isExistingClient(clientId);
    }

    @Test
    public void when_revoke_token_get_access_token_expired_then_return_true() throws Exception {
        // GIVEN

        String accessToken = "9376e098e8190835a0b41d83355f92d66f425469";
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"access_token\":" + accessToken + "," +
            "\"client_id\":" + clientId + "}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        willReturn(true).given(authServer.clientCredentialsService).isExistingClient(clientId);
        //willReturn(clientId).given(authServer).getBasicAuthorizationClientId(req);

        AccessToken dbAccessToken = mock(AccessToken.class);
        willReturn(true).given(dbAccessToken).tokenExpired();
        willReturn(dbAccessToken).given(authServer.db).findAccessToken(accessToken);

        // WHEN
        boolean revoked = authServer.revokeToken(req);

        // THEN
        assertTrue(revoked);
    }

    @Test
    public void when_revoke_token_invoke_remove_token() throws Exception {
        // GIVEN

        String accessToken = "9376e098e8190835a0b41d83355f92d66f425469";
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"access_token\":" + accessToken + "," +
            "\"client_id\":" + clientId + "}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        willReturn(true).given(authServer.clientCredentialsService).isExistingClient(clientId);

        AccessToken dbAccessToken = mock(AccessToken.class);
        willReturn(false).given(dbAccessToken).tokenExpired();
        willReturn(clientId).given(dbAccessToken).getClientId();
        willReturn(accessToken).given(dbAccessToken).getToken();
        willReturn(dbAccessToken).given(authServer.db).findAccessToken(accessToken);
        willDoNothing().given(authServer.db).removeAccessToken(accessToken);

        // WHEN
        boolean revoked = authServer.revokeToken(req);

        // THEN
        verify(authServer.db).removeAccessToken(accessToken);
        assertTrue(revoked);
    }

    @Test
    public void when_revoke_token_issued_with_other_client_id_do_not_expire_and_return_false()
            throws Exception {
        // GIVEN

        String accessToken = "9376e098e8190835a0b41d83355f92d66f425469";
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"access_token\":" + accessToken + "," +
            "\"client_id\":" + clientId + "}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        willReturn(true).given(authServer.clientCredentialsService).isExistingClient(clientId);

        AccessToken dbAccessToken = mock(AccessToken.class);
        willReturn(false).given(dbAccessToken).tokenExpired();
        willReturn("0345901231313").given(dbAccessToken).getClientId();
        willReturn(dbAccessToken).given(authServer.db).findAccessToken(accessToken);

        // WHEN
        boolean revoked = authServer.revokeToken(req);

        // THEN
        verify(authServer.db, times(0)).updateAccessTokenValidStatus(dbAccessToken.getToken(), false);
        assertFalse(revoked);
    }

    @Test
    public void when_revoke_token_with_invalid_client_credentials_return_bad_request()
            throws Exception {
        // GIVEN

        String clientSecret = "bb635eb22c5b5ce3de06e31bb88be7ae";
        String accessToken = "9376e098e8190835a0b41d83355f92d66f425469";
        HttpRequest req = mock(HttpRequest.class);
        String content = "{\"access_token\":" + accessToken + "," +
            "\"client_id\":" + clientId + ",\"client_secret\":" + clientSecret + "}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        willReturn(false).given(authServer.clientCredentialsService).isValidClientCredentials(clientId, clientSecret);

        // WHEN
        String errorMsg = null;
        HttpResponseStatus status = null;
        try {
            authServer.revokeToken(req);
        } catch(OAuthException e) {
            errorMsg = e.getMessage();
            status = e.getHttpStatus();
        }

        // THEN
        assertEquals(errorMsg, ClientCredentialsService.INACTIVE_CLIENT_CREDENTIALS);
        assertEquals(status, HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    public void when_issue_auth_code_check_scope_valid() throws Exception {
        HttpRequest req = mock(HttpRequest.class);

        ClientCredentials client = mock(ClientCredentials.class);
        given(client.getStatus()).willReturn(ClientCredentials.ACTIVE_STATUS);
        given(authServer.db.findClientCredentials(clientId)).willReturn(client);

        given(req.getUri()).willReturn(
              "http://example.com/oauth20/authorize?redirect_uri=http%3A%2F%2Fexample.com&response_type=code&client_id="
              + clientId);
        willReturn("basic").given(authServer.scopeService).getValidScope(null, clientId);

        // WHEN
        authServer.issueAuthorizationCode(req);

        // THEN
        verify(authServer).generateCode();
        verify(authServer.scopeService).getValidScope(null, clientId);
    }

    @Test
    public void when_issue_auth_code_with_invalid_scope_return_error() throws Exception {
        HttpRequest req = mock(HttpRequest.class);

        String scope = "nonexist";
        ClientCredentials client = mock(ClientCredentials.class);
        given(client.getStatus()).willReturn(ClientCredentials.ACTIVE_STATUS);
        given(authServer.db.findClientCredentials(clientId)).willReturn(client);
        given(req.getUri())
                .willReturn(
                        "http://example.com/oauth20/authorize?redirect_uri=http%3A%2F%2Fexample.com&response_type=code&client_id="
                                + clientId + "&scope=" + scope);
        willReturn(null).given(authServer.scopeService).getValidScope(scope, clientId);

        // WHEN
        String errorMsg = null;
        Integer httpStatus = null;
        try {
            authServer.issueAuthorizationCode(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
            httpStatus = e.getHttpStatus().getCode();
        }

        // THEN
        assertEquals(errorMsg, new TokenError(TokenErrorTypes.INVALID_SCOPE).toString());
        assertEquals(httpStatus, Integer.valueOf(HttpStatus.SC_BAD_REQUEST));
    }

    @Test
    public void when_handle_client_creds_token_with_no_scope_set_client_app_scope()
            throws Exception {
        HttpRequest req = mock(HttpRequest.class);
        String content = "grant_type=" + TokenRequest.CLIENT_CREDENTIALS;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        ClientCredentials clientCredentials = new ClientCredentials();
        clientCredentials.setScope("basic");
        clientCredentials.setId(clientId);
        given(authServer.db.findClientCredentials(clientId)).willReturn(clientCredentials);
        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        willReturn("basic").given(authServer.scopeService).getValidScopeByScope(null, "basic");

        // WHEN
        AccessToken accessToken = authServer.issueAccessToken(req);

        // THEN
        assertEquals(accessToken.getScope(), "basic");
    }

    @Test
    public void when_handle_client_creds_token_with_invalid_scope_return_error_message()
            throws Exception {
        HttpRequest req = mock(HttpRequest.class);
        String content = "grant_type=" + TokenRequest.CLIENT_CREDENTIALS + "&scope=ext";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        ClientCredentials clientCredentials = new ClientCredentials();
        clientCredentials.setScope("basic");
        clientCredentials.setId(clientId);
        given(authServer.db.findClientCredentials(clientId)).willReturn(clientCredentials);
        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        willReturn(null).given(authServer.scopeService).getValidScopeByScope("ext", clientId);

        // WHEN
        String errorMsg = null;
        try {
            authServer.issueAccessToken(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        assertEquals(errorMsg, new TokenError(TokenErrorTypes.INVALID_SCOPE).toString());
    }

    @Test
    public void when_handle_password_token_with_no_scope_set_client_app_scope() throws Exception {
        HttpRequest req = mock(HttpRequest.class);
        String content = "grant_type=" + TokenRequest.PASSWORD + "&username=user&password=pass";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        willReturn("basic").given(authServer.scopeService).getValidScope(null, clientId);
        UserDetails userDetails = new UserDetails("23433366", null);
        willReturn(userDetails).given(authServer).authenticateUser(anyString(), anyString(), any(HttpRequest.class));

        // WHEN
        AccessToken accessToken = authServer.issueAccessToken(req);

        // THEN
        assertEquals(accessToken.getScope(), "basic");
    }

    @Test
    public void when_handle_password_token_with_invalid_scope_return_error_message()
            throws Exception {
        HttpRequest req = mock(HttpRequest.class);
        String content = "grant_type=" + TokenRequest.PASSWORD
                + "&username=user&password=pass&scope=ext";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        willReturn(null).given(authServer.scopeService).getValidScope("ext", clientId);

        // WHEN
        String errorMsg = null;
        try {
            authServer.issueAccessToken(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        assertEquals(errorMsg, new TokenError(TokenErrorTypes.INVALID_SCOPE).toString());
    }

    @Test
    public void when_refresh_token_with_null_scope_use_access_token_scope() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String refreshToken = "403b510679013ea1813b6fb5f76e7ddfedb8852d9eb8eef73";
        String content = "grant_type=" + TokenRequest.REFRESH_TOKEN + "&refresh_token="
                + refreshToken;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        AccessToken accessToken = mock(AccessToken.class);
        willReturn("02d31ca13a0e448802b063ca2e16010b74b0e96ce9e05e953e").given(accessToken).getToken();
        willReturn("basic").given(accessToken).getScope();
        willReturn(accessToken).given(authServer.db).findAccessTokenByRefreshToken(refreshToken, clientId);
        willDoNothing().given(authServer.db).updateAccessTokenValidStatus(anyString(), anyBoolean());
        willDoNothing().given(authServer.db).storeAccessToken(any(AccessToken.class));

        // WHEN
        AccessToken result = authServer.issueAccessToken(req);

        // THEN
        assertEquals(result.getScope(), "basic");
    }

    @Test
    public void when_refresh_token_with_scope_contained_in_access_token_scope_use_that_scope()
            throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String refreshToken = "403b510679013ea1813b6fb5f76e7ddfedb8852d9eb8eef73";
        String content = "grant_type=" + TokenRequest.REFRESH_TOKEN + "&refresh_token="
                + refreshToken + "&scope=extended";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        AccessToken accessToken = mock(AccessToken.class);
        willReturn("02d31ca13a0e448802b063ca2e16010b74b0e96ce9e05e953e").given(accessToken).getToken();
        willReturn("basic, extended").given(accessToken).getScope();
        willReturn(true).given(authServer.scopeService).scopeAllowed(anyString(), anyString());
        willReturn(accessToken).given(authServer.db).findAccessTokenByRefreshToken(refreshToken, clientId);
        willDoNothing().given(authServer.db).updateAccessTokenValidStatus(anyString(), anyBoolean());
        willDoNothing().given(authServer.db).storeAccessToken(any(AccessToken.class));

        // WHEN
        AccessToken result = authServer.issueAccessToken(req);

        // THEN
        assertEquals(result.getScope(), "extended");
    }

    @Test
    public void when_refresh_token_with_scope_not_contained_in_access_token_scope_return_error_message()
            throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String refreshToken = "403b510679013ea1813b6fb5f76e7ddfedb8852d9eb8eef73";
        String content = "grant_type=" + TokenRequest.REFRESH_TOKEN + "&refresh_token="
                + refreshToken + "&scope=extended";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        AccessToken accessToken = mock(AccessToken.class);
        willReturn("02d31ca13a0e448802b063ca2e16010b74b0e96ce9e05e953e").given(accessToken).getToken();
        willReturn("basic, extended").given(accessToken).getScope();
        willReturn(false).given(authServer.scopeService).scopeAllowed(anyString(), anyString());
        willReturn(accessToken).given(authServer.db).findAccessTokenByRefreshToken(refreshToken, clientId);
        willDoNothing().given(authServer.db).updateAccessTokenValidStatus(anyString(), anyBoolean());
        willDoNothing().given(authServer.db).storeAccessToken(any(AccessToken.class));

        // WHEN
        String errorMsg = null;
        try {
            authServer.issueAccessToken(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        assertEquals(errorMsg, new TokenError(TokenErrorTypes.INVALID_SCOPE).toString());
    }

    @Test
    public void when_client_id_and_client_secret_passed_issue_client_credentials_with_them() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String clientId = "3242342342342";
        String clientSecret = "33196d652cb8e5bc2edfc95722ebb452f7fc3ef9";
        String content = "{\"name\":\"name\",\"redirect_uri\":\"http://example.com\", \"scope\":\"basic\", " +
                "\"client_id\":\"" + clientId + "\", \"client_secret\":\"" + clientSecret + "\"}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn(Response.APPLICATION_JSON).given(headers).get(HttpHeaders.Names.CONTENT_TYPE);
        willReturn(headers).given(req).headers();
        Scope scope = new Scope();
        willReturn(scope).given(authServer.db).findScope("basic");

        // WHEN
        ClientCredentials creds = authServer.clientCredentialsService.issueClientCredentials(req);

        // THEN
        assertEquals(creds.getId(), clientId);
        assertEquals(creds.getSecret(), clientSecret);
    }

    @Test
    public void when_client_id_only_passed_issue_client_credentials_with_generated_client_id_and_client_secret() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String clientId = "3242342342342";
        String content = "{\"name\":\"name\",\"redirect_uri\":\"http://example.com\", \"scope\":\"basic\", " +
                "\"client_id\":\"" + clientId + "\"}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn(Response.APPLICATION_JSON).given(headers).get(HttpHeaders.Names.CONTENT_TYPE);
        willReturn(headers).given(req).headers();
        Scope scope = new Scope();
        willReturn(scope).given(authServer.db).findScope("basic");

        // WHEN
        ClientCredentials creds = authServer.clientCredentialsService.issueClientCredentials(req);

        // THEN
        assertNotEquals(creds.getId(), clientId);
    }

    @Test
    public void when_client_secret_only_passed_issue_client_credentials_with_generated_client_id_and_client_secret() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String clientSecret = "33196d652cb8e5bc2edfc95722ebb452f7fc3ef9";
        String content = "{\"name\":\"name\",\"redirect_uri\":\"http://example.com\", \"scope\":\"basic\", " +
                "\"client_secret\":\"" + clientSecret + "\"}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn(Response.APPLICATION_JSON).given(headers).get(HttpHeaders.Names.CONTENT_TYPE);
        willReturn(headers).given(req).headers();
        Scope scope = new Scope();
        willReturn(scope).given(authServer.db).findScope("basic");

        // WHEN
        ClientCredentials creds = authServer.clientCredentialsService.issueClientCredentials(req);

        // THEN
        assertNotEquals(creds.getSecret(), clientSecret);
    }

    @Test
    public void when_client_id_already_registered_then_throw_exception() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String clientId = "3242342342342";
        String clientSecret = "33196d652cb8e5bc2edfc95722ebb452f7fc3ef9";
        String content = "{\"name\":\"name\",\"redirect_uri\":\"http://example.com\", \"scope\":\"basic\", " +
                "\"client_id\":\"" + clientId + "\", \"client_secret\":\"" + clientSecret + "\"}";
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        willReturn(buf).given(req).getContent();
        HttpHeaders headers = mock(HttpHeaders.class);
        willReturn(Response.APPLICATION_JSON).given(headers).get(HttpHeaders.Names.CONTENT_TYPE);
        willReturn(headers).given(req).headers();
        Scope scope = new Scope();
        willReturn(scope).given(authServer.db).findScope("basic");
        ClientCredentials registredCreds = new ClientCredentials();
        willReturn(registredCreds).given(authServer.db).findClientCredentials(clientId);

        // WHEN
        String errorMsg = null;
        try {
            authServer.clientCredentialsService.issueClientCredentials(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        assertEquals(errorMsg, ClientCredentialsService.ALREADY_REGISTERED_APP);
    }

    @Test
    public void when_client_id_and_client_secret_passed_in_request_body_generate_access_token() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String clientSecret = "f754cb0cd78c4c36fa3c1c0325ef72bb4a011373";
        String content = "grant_type=" + TokenRequest.PASSWORD + "&username=rossi&password=test&client_id=" +
                clientId + "&client_secret=" + clientSecret;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        willDoNothing().given(authServer.db).storeAccessToken(any(AccessToken.class));
        UserDetails userDetails = new UserDetails("123456", null);
        willReturn(userDetails).given(authServer).authenticateUser("rossi", "test", req);
        willReturn("basic").given(authServer.scopeService).getValidScope(null, clientId);

        // WHEN
        AccessToken result = authServer.issueAccessToken(req);

        // THEN
        verify(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        assertNotNull(result.getRefreshToken());
    }

    @Test
    public void when_client_secret_NOT_passed_and_NO_Basic_Auth_throw_exception() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String content = "grant_type=" + TokenRequest.PASSWORD + "&username=rossi&password=test&client_id=" + clientId;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        // WHEN
        String errorMsg = null;
        try {
            authServer.issueAccessToken(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        TokenError err = new TokenError(TokenErrorTypes.MANDATORY_PARAM_MISSING);
        err.setMessageParams("client_secret");
        assertEquals(errorMsg, err.toString());
    }

    @Test
    public void when_client_id_and_client_secret_NOT_valid_throw_exception() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String clientSecret = "f754cb0cd78c4c36fa3c1c0325ef72bb4a011373";
        String content = "grant_type=" + TokenRequest.PASSWORD + "&username=rossi&password=test&client_id=" +
                clientId + "&client_secret=" + clientSecret;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);
        willReturn(false).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);

        // WHEN
        String errorMsg = null;
        try {
            authServer.issueAccessToken(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        assertEquals(errorMsg, new TokenError(TokenErrorTypes.INVALID_CLIENT_CREDENTIALS).toString());
    }

    @Test
    public void when_grant_type_custom_invoke_custom_handler() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String clientSecret = "f754cb0cd78c4c36fa3c1c0325ef72bb4a011373";
        String content = "grant_type=" + grantType + "&username=rossi&password=test&client_id=" +
                clientId + "&client_secret=" + clientSecret;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        willReturn(null).given(authServer).callCustomGrantTypeHandler(req);
        willReturn("basic").given(authServer.scopeService).getValidScope(null, clientId);

        // WHEN
        authServer.issueAccessToken(req);

        // THEN
        verify(authServer).callCustomGrantTypeHandler(req);
    }

    @Test
    public void when_grant_type_custom_and_scope_not_valid_throw_exception() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);

        String clientSecret = "f754cb0cd78c4c36fa3c1c0325ef72bb4a011373";
        String content = "grant_type=" + grantType + "&username=rossi&password=test&client_id=" +
                clientId + "&client_secret=" + clientSecret;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);
        doReturn(true).when(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        doReturn(null).when(authServer).callCustomGrantTypeHandler(req);

        // WHEN
        String errorMsg = null;
        try {
            authServer.issueAccessToken(req);
        } catch (OAuthException e) {
            errorMsg = e.getMessage();
        }

        // THEN
        assertEquals(errorMsg, new TokenError(TokenErrorTypes.INVALID_SCOPE).toString());
    }

    @Test
    public void when_grant_type_custom_issue_token_with_user_details() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);

        String clientSecret = "f754cb0cd78c4c36fa3c1c0325ef72bb4a011373";
        String content = "grant_type=" + grantType  + "&username=rossi&password=test&client_id=" +
                clientId + "&client_secret=" + clientSecret;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        UserDetails userDetails = mock(UserDetails.class);
        willReturn("12345").given(userDetails).getUserId();
        willReturn(null).given(userDetails).getDetails();
        willReturn(userDetails).given(authServer).callCustomGrantTypeHandler(req);
        willReturn("basic").given(authServer.scopeService).getValidScope(null, clientId);

        // WHEN
        authServer.issueAccessToken(req);

        // THEN
        verify(userDetails, times(2)).getUserId();
        verify(userDetails).getDetails();
    }

    @Test
    public void when_update_client_app_with_invalid_client_id_throws_oauth_exception_with_bad_request_status() throws Exception {
        // GIVEN
        HttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, HttpRequestHandler.APPLICATION_URI + "/" + clientId);
        req.headers().add(HttpHeaders.Names.CONTENT_TYPE, Response.APPLICATION_JSON);
        String content = "any content here";
        req.setContent(ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8)));
        willReturn(false).given(authServer.clientCredentialsService).isExistingClient(clientId);

        // WHEN
        HttpResponseStatus status = null;
        String message = null;
        try {
            authServer.clientCredentialsService.updateClientCredentials(req, clientId);
        } catch(OAuthException e) {
            status = e.getHttpStatus();
            message = e.getMessage();
        }

        // THEN
        assertEquals(status, HttpResponseStatus.BAD_REQUEST);
        assertEquals(message, ClientCredentialsService.CLIENT_APP_DOES_NOT_EXIST);
        verify(authServer.clientCredentialsService).isExistingClient(clientId);
    }

    @Test
    public void when_delete_client_app_with_invalid_client_id_throws_oauth_exception_with_bad_request_status() throws Exception {
        // GIVEN
        willReturn(false).given(authServer.clientCredentialsService).isExistingClient(clientId);

        // WHEN
        HttpResponseStatus status = null;
        String message = null;
        try {
            authServer.clientCredentialsService.deleteClientCredentials(clientId);
        } catch(OAuthException e) {
            status = e.getHttpStatus();
            message = e.getMessage();
        }

        // THEN
        assertEquals(status, HttpResponseStatus.BAD_REQUEST);
        assertEquals(message, ClientCredentialsService.CLIENT_APP_DOES_NOT_EXIST);
        verify(authServer.clientCredentialsService).isExistingClient(clientId);
    }

    @Test
    public void when_client_app_status_is_inactive_return_validClientCredentials_false() throws Exception {
        // GIVEN
        String clientSecret = "f754cb0cd78c4c36fa3c1c0325ef72bb4a011373";
        ClientCredentials client = mock(ClientCredentials.class);
        willReturn(ClientCredentials.INACTIVE_STATUS).given(client).getStatus();
        willReturn(clientSecret).given(client).getSecret();
        willReturn(client).given(authServer.db).findClientCredentials(clientId);

        // WHEN
        boolean valid = authServer.clientCredentialsService.isActiveClient(clientId, clientSecret);

        // THEN
        assertFalse(valid);
    }

    @Test
    public void when_client_app_status_is_active_return_validClientCredentials_true() throws Exception {
        // GIVEN
        String clientSecret = "f754cb0cd78c4c36fa3c1c0325ef72bb4a011373";
        ClientCredentials creds = mock(ClientCredentials.class);
        willReturn(ClientCredentials.ACTIVE_STATUS).given(creds).getStatus();
        willReturn(clientSecret).given(creds).getSecret();
        willReturn(creds).given(authServer.db).findClientCredentials(clientId);

        // WHEN
        boolean valid = authServer.clientCredentialsService.isActiveClient(clientId, clientSecret);

        // THEN
        assertTrue(valid);
    }

    @Test
    public void when_client_app_status_is_inactive_but_status_not_checked_return_validClientCredentials_true() throws Exception {
        // GIVEN
        String clientSecret = "f754cb0cd78c4c36fa3c1c0325ef72bb4a011373";
        ClientCredentials creds = mock(ClientCredentials.class);
        willReturn(ClientCredentials.INACTIVE_STATUS).given(creds).getStatus();
        willReturn(clientSecret).given(creds).getSecret();
        willReturn(creds).given(authServer.db).findClientCredentials(clientId);

        // WHEN
        boolean valid = authServer.clientCredentialsService.isValidClientCredentials(clientId, clientSecret);

        // THEN
        assertTrue(valid);
    }

    @Test
    public void when_client_id_exists_return_true() throws Exception {
        // GIVEN
        willReturn(mock(ClientCredentials.class)).given(authServer.db).findClientCredentials(clientId);

        // WHEN
        boolean result = authServer.clientCredentialsService.isExistingClient(clientId);

        // THEN
        assertTrue(result);
    }

    @Test
    public void when_client_id_does_not_exist_return_false() throws Exception {
        // GIVEN
        willReturn(null).given(authServer.db).findClientCredentials(clientId);

        // WHEN
        boolean result = authServer.clientCredentialsService.isExistingClient(clientId);

        // THEN
        assertFalse(result);
    }

    @Test
    public void when_update_client_app_check_client_id_exists() throws Exception {
        // GIVEN
        HttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT,
                HttpRequestHandler.APPLICATION_URI + "/" + clientId);
        req.headers().add(HttpHeaders.Names.CONTENT_TYPE, Response.APPLICATION_JSON);
        String content = "{\"status\":\"1\"}";
        req.setContent(ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8)));
        willReturn(true).given(authServer.clientCredentialsService).isExistingClient(clientId);

        // WHEN
        authServer.clientCredentialsService.updateClientCredentials(req, clientId);

        // THEN
        verify(authServer.clientCredentialsService).isExistingClient(clientId);
    }

    @Test
    public void when_delete_client_app_check_client_id_exists() throws Exception {
        // GIVEN
        willReturn(true).given(authServer.clientCredentialsService).isExistingClient(clientId);
        willReturn(true).given(authServer.db).deleteClientApp(clientId);

        // WHEN
        authServer.clientCredentialsService.deleteClientCredentials(clientId);

        // THEN
        verify(authServer.clientCredentialsService).isExistingClient(clientId);
    }

    @Test
    public void when_issue_access_token_with_refresh_token_use_the_same_refresh_token() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String refreshToken = "403b510679013ea1813b6fb5f76e7ddfedb8852d9eb8eef73";
        String content = "grant_type=" + TokenRequest.REFRESH_TOKEN + "&refresh_token=" + refreshToken;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        AccessToken accessToken = mock(AccessToken.class);
        willReturn("basic").given(accessToken).getScope();
        willReturn("02d31ca13a0e448802b063ca2e16010b74b0e96ce9e05e953e").given(accessToken).getToken();
        willReturn(refreshToken).given(accessToken).getRefreshToken();
        willReturn(accessToken).given(authServer.db).findAccessTokenByRefreshToken(refreshToken, clientId);
        willDoNothing().given(authServer.db).updateAccessTokenValidStatus(anyString(), anyBoolean());
        willDoNothing().given(authServer.db).storeAccessToken(any(AccessToken.class));
        willReturn(900).given(authServer.scopeService).getExpiresIn(TokenRequest.PASSWORD, "basic");

        // WHEN
        AccessToken newAccessToken = authServer.issueAccessToken(req);

        // THEN
        assertEquals(newAccessToken.getRefreshToken(), refreshToken);
    }

    @Test
    public void when_refresh_token_expired_invoke_update_access_token_expired_status() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String refreshToken = "403b510679013ea1813b6fb5f76e7ddfedb8852d9eb8eef73";
        String content = "grant_type=" + TokenRequest.REFRESH_TOKEN + "&refresh_token=" + refreshToken;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);

        willReturn(getAuthorizationBasicHeader()).given(req).headers();
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);

        AccessToken accessToken = mock(AccessToken.class);
        willReturn("basic").given(accessToken).getScope();
        willReturn("02d31ca13a0e448802b063ca2e16010b74b0e96ce9e05e953e").given(accessToken).getToken();
        willReturn(refreshToken).given(accessToken).getRefreshToken();
        willReturn(accessToken).given(authServer.db).findAccessTokenByRefreshToken(refreshToken, clientId);
        willDoNothing().given(authServer.db).removeAccessToken(anyString());
        willReturn(true).given(accessToken).refreshTokenExpired();

        // WHEN
        try {
            authServer.issueAccessToken(req);
        } catch (OAuthException e) {
            // do nothing
        }

        // THEN
        verify(authServer.db).removeAccessToken(accessToken.getToken());
    }

    @Test
    public void when_issuing_application_token_it_should_have_application_details() throws Exception {
        // GIVEN
        HttpRequest req = mock(HttpRequest.class);
        String clientSecret = "f754cb0cd78c4c36fa3c1c0325ef72bb4a011373";
        String content = "grant_type=" + TokenRequest.CLIENT_CREDENTIALS + "&client_id=" +
                clientId + "&client_secret=" + clientSecret;
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content.getBytes(CharsetUtil.UTF_8));
        given(req.getContent()).willReturn(buf);
        willReturn(true).given(authServer.clientCredentialsService).isActiveClient(clientId, clientSecret);
        ClientCredentials clientCredentials = new ClientCredentials();
        clientCredentials.setScope("basic");
        clientCredentials.setId(clientId);
        Map<String, String> applicationDetails = new HashMap<String, String>();
        applicationDetails.put("my", "data");
        clientCredentials.setApplicationDetails(applicationDetails );
        given(authServer.db.findClientCredentials(clientId)).willReturn(clientCredentials);
        willReturn("basic").given(authServer.scopeService).getValidScopeByScope(anyString(), anyString());

        // WHEN
        AccessToken accessToken = authServer.issueAccessToken(req);

        // THEN
        assertNotNull(accessToken.getDetails());
   }
    private HttpHeaders getAuthorizationBasicHeader() {
        // clientId:clientSecret, 203598599234220:105ef93e7bb386da3a23c32e8563434fad005fd0a6a88315fcdf946aa761c838
        String basicHeader = "Basic MjAzNTk4NTk5MjM0MjIwOjEwNWVmOTNlN2JiMzg2ZGEzYTIzYzMyZTg1NjM0MzRmYWQwMDVmZDBhNmE4ODMxNWZjZGY5NDZhYTc2MWM4Mzg=";
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set(HttpHeaders.Names.AUTHORIZATION, basicHeader);
        return headers;
    }
}
