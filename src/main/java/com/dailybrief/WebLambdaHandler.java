package com.dailybrief;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequest;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WebLambdaHandler implements RequestStreamHandler {
    private static final ObjectMapper JSON = SpringBootLambdaContainerHandler.getObjectMapper();
    private static final SpringBootLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse>
            HANDLER = initialize();

    private static SpringBootLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse> initialize() {
        try {
            return SpringBootLambdaContainerHandler.getHttpApiV2ProxyHandler(
                    DailyBriefApplication.class, "web-lambda");
        } catch (ContainerInitializationException error) {
            throw new IllegalStateException("Unable to start the Daily Brief web Lambda", error);
        }
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        var request = JSON.readValue(input, HttpApiV2ProxyRequest.class);
        var response = HANDLER.proxy(request, context);
        var headers = new LinkedHashMap<String, String>();
        var cookies = new ArrayList<String>();
        // HTTP API payload 2.0 carries each Set-Cookie value in the response's cookies array.
        if (response.getMultiValueHeaders() != null) {
            response.getMultiValueHeaders().forEach((name, values) -> {
                if (HttpHeaders.SET_COOKIE.equalsIgnoreCase(name)) cookies.addAll(values);
                else headers.put(name, String.join(",", values));
            });
        } else if (response.getHeaders() != null) {
            response.getHeaders().forEach((name, value) -> {
                if (HttpHeaders.SET_COOKIE.equalsIgnoreCase(name)) cookies.add(value);
                else headers.put(name, value);
            });
        }
        JSON.writeValue(output, new HttpApiResponse(response.getStatusCode(), headers, cookies,
                response.getBody() == null ? "" : response.getBody(), response.isBase64Encoded()));
    }

    private record HttpApiResponse(int statusCode, Map<String, String> headers, List<String> cookies,
            String body, boolean isBase64Encoded) {}
}
