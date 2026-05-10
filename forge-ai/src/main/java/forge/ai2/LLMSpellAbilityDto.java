package forge.ai2;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LLMSpellAbilityDto {

    private String thoughts;

    @JsonProperty("spell_ability_description")
    private String spellAbilityDescription;

    public LLMSpellAbilityDto() {}

    public LLMSpellAbilityDto(String thoughts, String spellAbilityDescription) {
        this.thoughts = thoughts;
        this.spellAbilityDescription = spellAbilityDescription;
    }

    public String getThoughts() {
        return thoughts;
    }

    public void setThoughts(String thoughts) {
        this.thoughts = thoughts;
    }

    public String getSpellAbilityDescription() {
        return spellAbilityDescription;
    }

    public void setSpellAbilityDescription(String spellAbilityDescription) {
        this.spellAbilityDescription = spellAbilityDescription;
    }

    @Override
    public String toString() {
        return "LLMSpellAbilityDto{thoughts='" + thoughts + "', spellAbilityDescription='" + spellAbilityDescription + "'}";
    }
}