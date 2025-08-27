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

import java.util.Optional;

import org.eclipse.jetty.client.util.StringContentProvider;

public class LLMApi {

    ObjectMapper mapper;
    HttpClient client;
    boolean notStarted = true;

    public LLMApi() {
        client = new HttpClient();
        mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    public void onExit() throws Exception {
        client.stop();
    }

    public String call(Game game, Player activePlayer) throws Exception {

        if (notStarted) {
            client.start();
            notStarted = false;
        }

        var gameState = GameStateMapper.mapGameState(game, activePlayer);
        var json = mapper.writeValueAsString(gameState);
        var requestBody = buildLLMRequest(json);

        ContentResponse response = client.POST("http://127.0.0.1:1234/v1/chat/completions")
                .header("Content-Type", "application/json")
                .content(new StringContentProvider(requestBody, "UTF-8"))
                .send();

        int statusCode = response.getStatus();
        String responseBody = response.getContentAsString();

        System.out.println("Status: " + statusCode);

        try {
            LLMResponseDto llmResponseDto = mapper.readValue(responseBody, LLMResponseDto.class);

            var contentDto = parseFirstMessageContent(llmResponseDto);

            if (contentDto.isPresent()) {
                System.out.println("Card Name: " + contentDto.get().getCardName());
                System.out.println("Thoughts: " + contentDto.get().getThoughts());

                return contentDto.get().getCardName();
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        throw new RuntimeException("Failed to parse response content from LLM API");
    }


    /**
     * Parses the JSON content from the first message in the first choice.
     *
     * @param response The ChatCompletionResponse to parse
     * @return Optional containing the parsed ContentData, empty if parsing fails or data is missing
     */
    public Optional<LLMContentDto> parseFirstMessageContent(LLMResponseDto response) {
        try {
            // Validate response structure
            if (response == null ||
                    response.getChoices() == null ||
                    response.getChoices().isEmpty()) {
                return Optional.empty();
            }

            Choice firstChoice = response.getChoices().get(0);
            if (firstChoice.getMessage() == null ||
                    firstChoice.getMessage().getContent() == null) {
                return Optional.empty();
            }

            String jsonContent = firstChoice.getMessage().getContent();

            // Parse the JSON string content
            LLMContentDto contentData = mapper.readValue(jsonContent, LLMContentDto.class);
            return Optional.of(contentData);

        } catch (JsonProcessingException e) {
            // Log the error in a real application
            System.err.println("Failed to parse JSON content: " + e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            System.err.println("Unexpected error parsing content: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Convenience method to extract just the card name from the first message.
     *
     * @param response The ChatCompletionResponse to parse
     * @return Optional containing the card name, empty if parsing fails or data is missing
     */
    public Optional<String> extractCardName(LLMResponseDto response) {
        return parseFirstMessageContent(response)
                .map(LLMContentDto::getCardName);
    }

    /**
     * Convenience method to extract just the thoughts from the first message.
     *
     * @param response The ChatCompletionResponse to parse
     * @return Optional containing the thoughts, empty if parsing fails or data is missing
     */
    public Optional<String> extractThoughts(LLMResponseDto response) {
        return parseFirstMessageContent(response)
                .map(LLMContentDto::getThoughts);
    }

    private String buildLLMRequest(String gameStateJson) throws Exception {
        // Root object
        ObjectNode root = mapper.createObjectNode();

        // Messages array
        ArrayNode messages = mapper.createArrayNode();

        // System message
        ObjectNode systemMessage = mapper.createObjectNode();
        systemMessage.put("role", "system");
        systemMessage.put("content",
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
                        "Remember to always be concise and decisive."
        );
        messages.add(systemMessage);

        // User message
        ObjectNode userMessage = mapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content",
                "Select ONE land to play from the lands in hand. Respond with this REQUIRED JSON format:\n\n" +
                        "{\n" +
                        "  \"thoughts\": \"(Brief reasoning on why the land is chosen, max 50 words)\",\n" +
                        "  \"card_name\": \"(Land name from hand)\"\n" +
                        "}\n\n" +
                        "IMPORTANT: Available lands are in the 'hand' array below. Choose based on mana needed for other spells in hand.\n\n" +
                        "GAME STATE:\n" + gameStateJson +
                        "YOUR VERY IMPORTANT TASK: GIVE ME THE JSON WITH THE DECISION OF THE BEST ONE LAND TO PLAY!"
        );
        messages.add(userMessage);

        root.set("messages", messages);

        // Response format
        ObjectNode responseFormat = mapper.createObjectNode();
        responseFormat.put("type", "json_schema");

        // JSON schema object
        ObjectNode jsonSchema = mapper.createObjectNode();
        jsonSchema.put("name", "mtg_response");
        jsonSchema.put("strict", true); // boolean, not string

        // Schema object
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        // Properties
        ObjectNode properties = mapper.createObjectNode();
        ObjectNode thoughts = mapper.createObjectNode();
        thoughts.put("type", "string");
        properties.set("thoughts", thoughts);
        ObjectNode cardName = mapper.createObjectNode();
        cardName.put("type", "string");
        properties.set("card_name", cardName);
        schema.set("properties", properties);

        // Required array - FIXED: should match the property name
        ArrayNode required = mapper.createArrayNode();
        required.add("card_name"); // Fixed from "best_card_to_play"
        required.add("thoughts"); // Fixed from "best_card_to_play"
        schema.set("required", required);

        jsonSchema.set("schema", schema);
        responseFormat.set("json_schema", jsonSchema);
        root.set("response_format", responseFormat);

        // Other properties
        root.put("temperature", 0.5);
        root.put("max_tokens", 1000);
        root.put("stream", false);

        return mapper.writeValueAsString(root);
    }

}
