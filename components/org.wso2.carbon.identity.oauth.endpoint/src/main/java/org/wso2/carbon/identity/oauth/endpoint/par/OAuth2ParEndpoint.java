/**
 * Copyright (c) 2023, WSO2 LLC. (https://www.wso2.com). All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.oauth.endpoint.par;

import org.apache.catalina.util.ParameterMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.interceptor.InInterceptors;
import org.json.JSONObject;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.oauth.client.authn.filter.OAuthClientAuthenticatorProxy;
import org.wso2.carbon.identity.oauth.common.OAuth2ErrorCodes;
import org.wso2.carbon.identity.oauth.common.OAuthConstants;
import org.wso2.carbon.identity.oauth.endpoint.exception.ParErrorDTO;
import org.wso2.carbon.identity.oauth.endpoint.util.EndpointUtil;
import org.wso2.carbon.identity.oauth.par.cache.CacheBackedParDAO;
import org.wso2.carbon.identity.oauth.par.common.ParConstants;
import org.wso2.carbon.identity.oauth.par.dao.ParDAOFactory;
import org.wso2.carbon.identity.oauth.par.dao.ParMgtDAO;
import org.wso2.carbon.identity.oauth.par.exceptions.ParCoreException;
import org.wso2.carbon.identity.oauth.par.model.ParAuthResponseData;
import org.wso2.carbon.identity.oauth.par.model.ParRequest;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2ServerException;
import org.wso2.carbon.identity.oauth2.dto.OAuth2ClientValidationResponseDTO;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.wso2.carbon.identity.oauth.endpoint.util.EndpointUtil.getOAuth2Service;

/**
 * REST implementation for OAuth2 PAR endpoint.
 */
@Path("/par")
@InInterceptors(classes = OAuthClientAuthenticatorProxy.class)
public class OAuth2ParEndpoint {

    private static final Log log = LogFactory.getLog(OAuth2ParEndpoint.class);
    private static final ParMgtDAO parMgtDAO = ParDAOFactory.getInstance().getParAuthMgtDAO();
    private static long scheduledExpiryTime;

    @POST
    @Path("/")
    @Consumes("application/x-www-form-urlencoded")
    @Produces("application/json")
    public Response par(@Context HttpServletRequest request, @Context HttpServletResponse response) throws ParErrorDTO, Exception {

        OAuth2ClientValidationResponseDTO validationResponse = validateClient(request);

        if (!validationResponse.isValidClient()) {

            return createErrorResponse(validationResponse);
        }
        if (isRequestUriProvided(request.getParameterMap())) {

            // passes par error object to obtain error response
            return createErrorResponse(rejectRequestWithRequestUri());
        }

        setScheduledExpiryTime(Calendar.getInstance(TimeZone.getTimeZone(ParConstants.UTC)).getTimeInMillis());

        HashMap<String, String> parameters = new HashMap<>();
        for (ParameterMap.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue()[0];
            parameters.put(key, value);
        }

        // get response
        ParAuthResponseData parAuthResponseData = getParAuthResponseData(response, request);
        Response parResponse = createAuthResponse(response, parAuthResponseData);

        persistParRequest(parAuthResponseData.getUuid(), parameters, scheduledExpiryTime);

        return parResponse;
    }

    /**
     * Schedules the expity time for the request made.
     *
     * @param requestedTime time tht the request was made.
     */
    private static void setScheduledExpiryTime(long requestedTime) {
        long defaultExpiryInSecs = ParConstants.EXPIRES_IN_DEFAULT_VALUE_IN_SEC * ParConstants.SEC_TO_MILLISEC_FACTOR;
        scheduledExpiryTime = requestedTime + defaultExpiryInSecs;
    }

    /**
     * Creates PAR AuthenticationResponse.
     *
     * @param response            Authentication response object.
     * @return Response for AuthenticationRequest.
     */
    private ParAuthResponseData getParAuthResponseData(@Context HttpServletResponse response,
                                                       HttpServletRequest request) {

        return EndpointUtil.getParAuthService().generateParAuthResponse(response, request);
    }

    /**
     * Creates PAR AuthenticationResponse.
     *
     * @return Response for AuthenticationRequest.
     */
    public Response createAuthResponse(HttpServletResponse response, ParAuthResponseData parAuthResponseData) {

        log.debug("Setting ExpiryTime for the response to the  request.");

        response.setContentType(MediaType.APPLICATION_JSON);

        net.minidev.json.JSONObject parAuthResponse = new net.minidev.json.JSONObject();
        parAuthResponse.put(ParConstants.REQUEST_URI, ParConstants.REQUEST_URI_HEAD + parAuthResponseData.getUuid());
        parAuthResponse.put(ParConstants.EXPIRES_IN, parAuthResponseData.getExpityTime());

        log.debug("Creating PAR Authentication response to the request");

        Response.ResponseBuilder responseBuilder = Response.status(HttpServletResponse.SC_CREATED);
        log.debug("Returning PAR Authentication Response for the request");

        return responseBuilder.entity(parAuthResponse.toString()).build();
    }

    /**
     * Creates PAR Authentication Error Response.
     *
     * @param oAuth2ClientValidationResponseDTO PAR Authentication Failed Exception.
     * @return response Authentication Error Responses for AuthenticationRequest.
     */
    private Response createErrorResponse(OAuth2ClientValidationResponseDTO oAuth2ClientValidationResponseDTO)
            throws IdentityOAuth2ServerException {

        // Create PAR Authentication Error Response.
        log.debug("Creating Error Response for PAR Authentication Request.");

        if (oAuth2ClientValidationResponseDTO.getErrorCode().equals(OAuth2ErrorCodes.SERVER_ERROR)) {
            return handleServerException(oAuth2ClientValidationResponseDTO);
        } else {
            return handleClientException(oAuth2ClientValidationResponseDTO);
        }
    }

    /**
     * Creates PAR Authentication Error Response.
     *
     * @param parErrorDTO PAR Authentication Failed Exception.
     * @return response Authentication Error Responses for AuthenticationRequest.
     */
    private Response createErrorResponse(ParErrorDTO parErrorDTO) {

        // Create PAR Authentication Error Response.
        log.debug("Creating Error Response for PAR Authentication Request.");

        if (parErrorDTO.getErrorCode().equals(OAuth2ErrorCodes.INVALID_REQUEST)) {
            return handleClientException(parErrorDTO);
        } else {
            throw new IllegalArgumentException("Invalid PAR error code");
        }
    }

    /**
     * Handles client exception.
     *
     * @param oAuth2ClientValidationResponseDTO Authentication Failure Exception.
     * @return Response for AuthenticationRequest.
     */
    private Response handleClientException(OAuth2ClientValidationResponseDTO oAuth2ClientValidationResponseDTO) {

        String errorCode = oAuth2ClientValidationResponseDTO.getErrorCode();
        JSONObject parErrorResponse = new JSONObject();
        parErrorResponse.put(OAuthConstants.OAUTH_ERROR, oAuth2ClientValidationResponseDTO.getErrorCode());
        parErrorResponse.put(OAuthConstants.OAUTH_ERROR_DESCRIPTION, oAuth2ClientValidationResponseDTO.getErrorMsg());

        Response.ResponseBuilder responseBuilder;
        if (errorCode.equals(OAuth2ErrorCodes.INVALID_CLIENT)) {
            responseBuilder = Response.status(HttpServletResponse.SC_UNAUTHORIZED);
        } else {
            responseBuilder = Response.status(HttpServletResponse.SC_BAD_REQUEST);
        }
        return responseBuilder.entity(parErrorResponse.toString()).build();
    }

    /**
     * Handles client exception.
     *
     * @param parErrorDTO Authentication Failure Exception.
     * @return Response for AuthenticationRequest.
     */
    private Response handleClientException(ParErrorDTO parErrorDTO) {

        JSONObject parErrorResponse = new JSONObject();
        parErrorResponse.put(OAuthConstants.OAUTH_ERROR, parErrorDTO.getErrorMsg());
        parErrorResponse.put(OAuthConstants.OAUTH_ERROR_DESCRIPTION, "request.with.request_uri.not.allowed");

        Response.ResponseBuilder responseBuilder;
        responseBuilder = Response.status(HttpServletResponse.SC_BAD_REQUEST);
        return responseBuilder.entity(parErrorResponse.toString()).build();
    }

    /**
     * Creates PAR invalid request Error Response.
     *
     * @return response PAR Bad request Error Responses for AuthenticationRequest.
     */
    private ParErrorDTO rejectRequestWithRequestUri() {

        ParErrorDTO parErrorDTO = new ParErrorDTO();
        parErrorDTO.setValidClient(false);
        parErrorDTO.setErrorCode(OAuth2ErrorCodes.INVALID_REQUEST);
        parErrorDTO.setErrorMsg("requestUri_provided");
        return parErrorDTO;
    }

    /**
     * Checks if request_uri parameter is provided in the PAR request.
     *
     * @param parameters parameter map.
     * @return Response for AuthenticationRequest.
     */
    private boolean isRequestUriProvided(Map<String, String[]> parameters) {

        return parameters.containsKey(OAuthConstants.OAuth20Params.REQUEST_URI);
    }

    /**
     * Handles server exception.
     *
     * @param oAuth2ClientValidationResponseDTO Authentication Failure Exception.
     * @return Response for AuthenticationRequest.
     */
    private Response handleServerException(OAuth2ClientValidationResponseDTO oAuth2ClientValidationResponseDTO) throws
            IdentityOAuth2ServerException {

        log.error(oAuth2ClientValidationResponseDTO);
        throw new IdentityOAuth2ServerException(oAuth2ClientValidationResponseDTO.getErrorMsg());
    }

    public static void persistParRequest(String uuid, HashMap<String, String> params, long scheduledExpiryTime)
            throws IdentityOAuth2Exception {

        try {
            // Store values to Database
            ParRequest parRequest;
            int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();


            getParMgtDAO().persistParRequest(uuid,
                    params.get(OAuthConstants.OAuth20Params.CLIENT_ID), scheduledExpiryTime, params);

            // Add data to cache
            parRequest = new ParRequest(uuid, params, scheduledExpiryTime);
            getCacheBackedParDAO().addParRequest(uuid, parRequest, tenantId);

        } catch (ParCoreException e) {
            throw new IdentityOAuth2Exception("Error occurred in persisting PAR request", e);
        }
    }

    private static CacheBackedParDAO getCacheBackedParDAO() {
        return new CacheBackedParDAO();
    }

    private static ParMgtDAO getParMgtDAO() {
        return parMgtDAO;
    }

    public static OAuth2ClientValidationResponseDTO validateClient(HttpServletRequest request) {

        return getOAuth2Service().validateClientInfo(request);
    }
}
