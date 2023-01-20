/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2023 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.model.ai.client;

import com.theokanning.openai.OpenAiService;
import com.theokanning.openai.completion.CompletionChoice;
import com.theokanning.openai.completion.CompletionRequest;
import org.eclipse.core.runtime.IProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.GPTPreferences;
import org.jkiss.dbeaver.model.ai.formatter.GPTRequestFormatter;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;
import retrofit2.HttpException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public class GPTAPIClient {
    //How many retries may be done if code 429 happens
    private static final int MAX_REQUEST_ATTEMPTS = 3;

    private static OpenAiService CLIENT_INSTANCE = null;

    /**
     * Initializes OpenAiService instance using token provided by {@link GPTPreferences} GTP_TOKEN_PATH
     */
    private static void initGPTApiClientInstance() throws DBException {
        if (CLIENT_INSTANCE != null) {
            return;
        }
        String token = acquireToken();
        if (CommonUtils.isEmpty(token)) {
            throw new DBException("Empty API token value");
        }
        CLIENT_INSTANCE = new OpenAiService(token, Duration.ofSeconds(30));
    }

    private static String acquireToken() throws DBException {
        return DBWorkbench.getPlatform().getPreferenceStore().getString(GPTPreferences.GPT_API_TOKEN);
    }

    /**
     * Request completion from GPT API uses parameters from {@link GPTPreferences} for model settings\
     * Adds current schema metadata to starting query
     *
     * @param request request text
     * @param monitor execution monitor
     * @param context context object
     * @return resulting string
     */
    @NotNull
    public static Optional<String> requestCompletion(
        @NotNull String request,
        @NotNull IProgressMonitor monitor,
        @Nullable DBSObjectContainer context
    ) throws DBException, HttpException {

        if (!getPreferenceStore().getBoolean(GPTPreferences.GPT_ENABLED)) {
            return Optional.empty();
        }
        if (CLIENT_INSTANCE == null) {
            initGPTApiClientInstance();
        }
        String modifiedRequest;
        modifiedRequest = GPTRequestFormatter.addDBMetadataToRequest(request, context, monitor);
        if (monitor.isCanceled()) {
            return Optional.empty();
        }
        CompletionRequest completionRequest = createCompletionRequest(modifiedRequest);
        monitor.subTask("Request GPT completion");
        try {
            if (monitor.isCanceled()) {
                return Optional.empty();
            }
            return tryCreateCompletion(completionRequest, 0);
        } finally {
            monitor.done();
        }
    }

    @NotNull
    private static Optional<String> tryCreateCompletion(@NotNull CompletionRequest completionRequest, int attempt)
        throws HttpException {
        if (attempt == MAX_REQUEST_ATTEMPTS) {
            return Optional.empty();
        }
        try {
            List<CompletionChoice> choices = CLIENT_INSTANCE.createCompletion(completionRequest).getChoices();
            Optional<CompletionChoice> choice = choices.stream().findFirst();
            return choice.map(completionChoice -> completionChoice.getText().substring(completionChoice.getIndex()));
        } catch (HttpException exception) {
            if (exception.code() == 429) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return Optional.empty();
                }
                return tryCreateCompletion(completionRequest, ++attempt);
            } else {
                throw exception;
            }
        }
    }

    private static DBPPreferenceStore getPreferenceStore() {
        return DBWorkbench.getPlatform().getPreferenceStore();
    }

    private static CompletionRequest createCompletionRequest(@NotNull String request) {
        int maxTokens = getPreferenceStore().getInt(GPTPreferences.GPT_MODEL_MAX_TOKENS);
        Double temperature = getPreferenceStore().getDouble(GPTPreferences.GPT_MODEL_TEMPERATURE);
        String model = getPreferenceStore().getString(GPTPreferences.GPT_MODEL);
        return CompletionRequest.builder()
            .prompt(request)
            .temperature(temperature)
            .maxTokens(maxTokens)
            .frequencyPenalty(0.0)
            .presencePenalty(0.0)
            .stop(List.of("#", ";"))
            .model(model)
            .echo(true)
            .build();
    }

    private GPTAPIClient() {

    }
}