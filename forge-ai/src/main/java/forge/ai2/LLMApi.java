package forge.ai2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        var gameState = GameStateMapper.mapGameState(game, activePlayer);

        LLMCardNameDto result = makeDecision(DecisionType.LAND_SELECTION, gameState, activePlayer, LLMCardNameDto.class);
        return result != null ? result.getCardName() : null;
    }

    public String chooseBestSpellAbilityToPlayFromList(List<SpellAbility> spellAbilities, Game game, Player activePlayer) throws Exception {
        if (spellAbilities == null || spellAbilities.isEmpty()) {
            return null;
        }

        // Create a context-enriched game state that includes the available spell abilities
        GameStateDto gameState = GameStateMapper.mapGameStateWithSpellAbilities(game, activePlayer, spellAbilities);
        LLMSpellAbilityDto result = makeDecision(DecisionType.SPELL_ABILITY_SELECTION, gameState, activePlayer, LLMSpellAbilityDto.class);

        if (result != null) {
            // VALIDATION: Check if response matches any available option
            String responseDesc = result.getSpellAbilityDescription();
            boolean isValid = spellAbilities.stream()
                    .anyMatch(sa -> sa.getDescription().equals(responseDesc));

            if (!isValid) {
                System.err.println("LLM chose invalid option: " + responseDesc);
                System.err.println("Valid options were: " +
                        spellAbilities.stream().map(SpellAbility::getDescription).collect(Collectors.toList()));

                // Fallback to first valid option
                throw new RuntimeException("ERROR!!!!");
            }

            return responseDesc.replaceAll("\\s-.*", "");
        }

        return null;
    }

    // Core decision-making method
    private <T> T makeDecision(DecisionType decisionType, GameStateDto gameState, Player activePlayer, Class<T> responseClass) throws Exception {
        if (notStarted) {
            client.start();
            notStarted = false;
        }

        String requestBody = buildRequest(decisionType, gameState);

        System.out.println("REQUEST TO LLM:\n" + requestBody);

        ContentResponse response = client.POST("http://127.0.0.1:1234/v1/chat/completions")
                .header("Content-Type", "application/json")
                .content(new StringContentProvider(requestBody, "UTF-8"))
                .send();

        int statusCode = response.getStatus();
        String responseBody = response.getContentAsString();

        System.out.println("Status: " + statusCode);
        System.out.println("responseBody:\n" + responseBody);

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

    // ENHANCEMENT #4: Split context into focused sections
    private ObjectNode createUserMessage(DecisionType decisionType, GameStateDto gameStateJson) throws JsonProcessingException {
        ObjectNode userMessage = mapper.createObjectNode();
        userMessage.put("role", "user");

        if (decisionType == DecisionType.SPELL_ABILITY_SELECTION && gameStateJson.availableSpellAbilities != null) {
            // Put constraints FIRST for spell ability selection
            StringBuilder content = new StringBuilder();

            content.append("=== VALID CHOICES (CHOOSE EXACTLY ONE) ===\n");
            for (int i = 0; i < gameStateJson.availableSpellAbilities.size(); i++) {
                content.append("OPTION ").append(i + 1).append(": \"")
                        .append(gameStateJson.availableSpellAbilities.get(i).description)
                        .append("\"\n");
            }
            content.append("\n");
            content.append("CRITICAL: Your spell_ability_description field MUST be copied EXACTLY from one of the options above.\n");
            content.append("Do NOT modify the text. Do NOT use text from anywhere else.\n");
            content.append("Copy and paste the exact description.\n\n");

            content.append("=== GAME CONTEXT ===\n");
            content.append(mapper.writeValueAsString(gameStateJson));

            userMessage.put("content", content.toString());
        } else {
            // Standard format for other decision types
            userMessage.put("content", decisionType.userPrompt + "\n\nGAME STATE:\n" + mapper.writeValueAsString(gameStateJson));
        }

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
        ),

        // ENHANCEMENT #3: Strengthened prompt constraints
        SPELL_ABILITY_SELECTION(
                "mtg_spell_ability_response",

                // STRENGTHENED SYSTEM PROMPT with explicit constraints
                "You are a Magic: The Gathering expert. You MUST follow these rules:\n\n" +
                        "CONSTRAINT #1: ONLY choose from the options listed in the user's message under \"VALID CHOICES\"\n" +
                        "CONSTRAINT #2: Copy the description EXACTLY - no changes, no additions, no modifications\n" +
                        "CONSTRAINT #3: Do NOT use text from any other part of the game state\n\n" +
                        "JSON RESPONSE FORMAT:\n" +
                        "{\n" +
                        "  \"thoughts\": \"Brief reasoning (max 50 words)\",\n" +
                        "  \"spell_ability_description\": \"EXACT text from VALID CHOICES list\"\n" +
                        "}\n\n" +
                        "DECISION PRIORITIES:\n" +
                        "1. Immediate threats that must be answered\n" +
                        "2. Game-winning opportunities  \n" +
                        "3. Board presence and advantage\n" +
                        "4. Mana efficiency\n" +
                        "5. Card advantage\n\n" +
                        "CRITICAL: Violating the constraints will cause system failure. Always copy exactly from the VALID CHOICES.",

                "You will see VALID CHOICES and GAME CONTEXT below.\n\n" +
                        "TASK: Choose exactly ONE option from VALID CHOICES and copy its description exactly into your JSON response.\n\n" +
                        "Response format:\n" +
                        "{\n" +
                        "  \"thoughts\": \"(Why this choice is best, max 50 words)\",\n" +
                        "  \"spell_ability_description\": \"(EXACT text copied from VALID CHOICES)\"\n" +
                        "}\n\n" +
                        "Remember: The spell_ability_description must be copied exactly - no modifications allowed.",

                0.3, 1200,

                mapper -> {
                    ObjectNode schema = mapper.createObjectNode();
                    schema.put("type", "object");

                    ObjectNode properties = mapper.createObjectNode();

                    ObjectNode thoughts = mapper.createObjectNode();
                    thoughts.put("type", "string");
                    properties.set("thoughts", thoughts);

                    ObjectNode spellAbilityDescription = mapper.createObjectNode();
                    spellAbilityDescription.put("type", "string");
                    properties.set("spell_ability_description", spellAbilityDescription);

                    schema.set("properties", properties);

                    ArrayNode required = mapper.createArrayNode();
                    required.add("thoughts");
                    required.add("spell_ability_description");
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