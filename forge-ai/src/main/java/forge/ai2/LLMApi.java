package forge.ai2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import forge.game.Game;
import forge.game.player.Player;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;

import java.util.function.Function;


/**
 * How to Add New Decision Types
 * Just add a new enum value:
 * ```
 * MULLIGAN_DECISION(
 *     "mtg_mulligan_response",
 *     "You are a Magic: The Gathering mulligan expert...",
 *     "Decide whether to keep or mulligan this opening hand...",
 *     0.3, 800,
 *     mapper -> {
 *         // Build JSON schema for mulligan response
 *         ObjectNode schema = mapper.createObjectNode();
 *         schema.put("type", "object");
 *         // ... schema definition
 *         return schema;
 *     }
 * )
 * ```
 * Then add the corresponding public method:
 * ```
 * public MulliganDecisionDto decideMulligan(Game game, Player activePlayer) throws Exception {
 *     return makeDecision(DecisionType.MULLIGAN_DECISION, game, activePlayer, MulliganDecisionDto.class);
 * }
 * ```
 */
public class LLMApi {

    private final ObjectMapper mapper;
    private final HttpClient client;
    private boolean notStarted = true;

    public LLMApi() {
        client = new HttpClient();
        mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    public void onExit() throws Exception {
        client.stop();
    }

    // Public API methods
    public String chooseBestLandToPlay(Game game, Player activePlayer) throws Exception {
        LLMCardNameDto result = makeDecision(DecisionType.LAND_SELECTION, game, activePlayer, LLMCardNameDto.class);
        return result != null ? result.getCardName() : null;
    }

    // Core decision-making method
    private <T> T makeDecision(DecisionType decisionType, Game game, Player activePlayer, Class<T> responseClass) throws Exception {
        if (notStarted) {
            client.start();
            notStarted = false;
        }

        var gameState = GameStateMapper.mapGameState(game, activePlayer);
        String requestBody = buildRequest(decisionType, gameState);

        ContentResponse response = client.POST("http://127.0.0.1:1234/v1/chat/completions")
                .header("Content-Type", "application/json")
                .content(new StringContentProvider(requestBody, "UTF-8"))
                .send();

        int statusCode = response.getStatus();
        String responseBody = response.getContentAsString();

        System.out.println("Status: " + statusCode);

        if (statusCode != 200) {
            throw new RuntimeException("HTTP request failed with status: " + statusCode);
        }

        try {
            LLMResponseDto llmResponse = mapper.readValue(responseBody, LLMResponseDto.class);
            return parseResponse(llmResponse, responseClass);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LLM response", e);
        }
    }

    // Generic response parser
    private <T> T parseResponse(LLMResponseDto response, Class<T> responseClass) {
        try {
            if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
                return null;
            }

            Choice firstChoice = response.getChoices().get(0);
            if (firstChoice.getMessage() == null || firstChoice.getMessage().getContent() == null) {
                return null;
            }

            String jsonContent = firstChoice.getMessage().getContent();
            return mapper.readValue(jsonContent, responseClass);

        } catch (JsonProcessingException e) {
            System.err.println("Failed to parse JSON content: " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.err.println("Unexpected error parsing content: " + e.getMessage());
            return null;
        }
    }

    // Request builder
    private String buildRequest(DecisionType decisionType, GameStateDto gameStateJson) throws Exception {
        ObjectNode root = mapper.createObjectNode();

        // Build messages
        ArrayNode messages = mapper.createArrayNode();
        messages.add(createSystemMessage(decisionType));
        messages.add(createUserMessage(decisionType, gameStateJson));
        root.set("messages", messages);

        // Add response format
        root.set("response_format", createResponseFormat(decisionType));

        // Standard parameters
        root.put("temperature", decisionType.temperature);
        root.put("max_tokens", decisionType.maxTokens);
        root.put("stream", false);

        return mapper.writeValueAsString(root);
    }

    private ObjectNode createSystemMessage(DecisionType decisionType) {
        ObjectNode systemMessage = mapper.createObjectNode();
        systemMessage.put("role", "system");
        systemMessage.put("content", decisionType.systemPrompt);
        return systemMessage;
    }

    private ObjectNode createUserMessage(DecisionType decisionType, GameStateDto gameStateJson) throws JsonProcessingException {
        ObjectNode userMessage = mapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", decisionType.userPrompt + "\n\nGAME STATE:\n" + mapper.writeValueAsString(gameStateJson));
        return userMessage;
    }

    private ObjectNode createResponseFormat(DecisionType decisionType) {
        ObjectNode responseFormat = mapper.createObjectNode();
        responseFormat.put("type", "json_schema");

        ObjectNode jsonSchema = mapper.createObjectNode();
        jsonSchema.put("name", decisionType.schemaName);
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", decisionType.schemaBuilder.apply(mapper));

        responseFormat.set("json_schema", jsonSchema);
        return responseFormat;
    }

    // Decision type configuration
    private enum DecisionType {
        LAND_SELECTION(
                "mtg_land_response",
                "You are a Magic: The Gathering land selection expert. You respond with JSON containing exactly two fields:\n\n" +
                        "JSON STRUCTURE:\n" +
                        "{\n" +
                        "  \"thoughts\": \"Brief reasoning (max 20 words)\",\n" +
                        "  \"card_name\": \"Exact land name from hand\"\n" +
                        "}\n\n" +
                        "THOUGHTS FIELD RULES:\n" +
                        "- Maximum 50 words total\n" +
                        "- State only: mana color needed + short reasoning\n" +
                        "CARD_NAME FIELD RULES:\n" +
                        "- Must exactly match a land name from the provided hand\n" +
                        "- No additional text\n\n" +
                        "Remember to always be concise and decisive.",

                "Select ONE land to play from the lands in hand. Respond with this REQUIRED JSON format:\n\n" +
                        "{\n" +
                        "  \"thoughts\": \"(Brief reasoning on why the land is chosen, max 50 words)\",\n" +
                        "  \"card_name\": \"(Land name from hand)\"\n" +
                        "}\n\n" +
                        "IMPORTANT: Available lands are in the 'hand' array below. Choose based on mana needed for other spells in hand.\n\n" +
                        "YOUR VERY IMPORTANT TASK: GIVE ME THE JSON WITH THE DECISION OF THE BEST ONE LAND TO PLAY!",

                0.5, 1000,

                mapper -> {
                    ObjectNode schema = mapper.createObjectNode();
                    schema.put("type", "object");

                    ObjectNode properties = mapper.createObjectNode();
                    ObjectNode thoughts = mapper.createObjectNode();
                    thoughts.put("type", "string");
                    properties.set("thoughts", thoughts);
                    ObjectNode cardName = mapper.createObjectNode();
                    cardName.put("type", "string");
                    properties.set("card_name", cardName);
                    schema.set("properties", properties);

                    ArrayNode required = mapper.createArrayNode();
                    required.add("card_name");
                    required.add("thoughts");
                    schema.set("required", required);

                    return schema;
                }
        );

        private final String schemaName;
        private final String systemPrompt;
        private final String userPrompt;
        private final double temperature;
        private final int maxTokens;
        private final Function<ObjectMapper, ObjectNode> schemaBuilder;

        DecisionType(String schemaName, String systemPrompt, String userPrompt, double temperature, int maxTokens, Function<ObjectMapper, ObjectNode> schemaBuilder) {
            this.schemaName = schemaName;
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
            this.schemaBuilder = schemaBuilder;
        }
    }
}