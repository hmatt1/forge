package forge.ai2;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LLMContentDto {

    private String thoughts;

    @JsonProperty("card_name")
    private String cardName;

    public LLMContentDto() {}

    public LLMContentDto(String thoughts, String cardName) {
        this.thoughts = thoughts;
        this.cardName = cardName;
    }

    public String getThoughts() { return thoughts; }
    public void setThoughts(String thoughts) { this.thoughts = thoughts; }

    public String getCardName() { return cardName; }
    public void setCardName(String cardName) { this.cardName = cardName; }

    @Override
    public String toString() {
        return "ContentData{thoughts='" + thoughts + "', cardName='" + cardName + "'}";
    }
}
