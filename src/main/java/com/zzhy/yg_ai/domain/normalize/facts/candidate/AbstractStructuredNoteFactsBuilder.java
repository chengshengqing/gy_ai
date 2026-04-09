package com.zzhy.yg_ai.domain.normalize.facts.candidate;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzhy.yg_ai.domain.normalize.support.NotePreparationSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class AbstractStructuredNoteFactsBuilder {

    private final NotePreparationSupport notePreparationSupport;

    protected AbstractStructuredNoteFactsBuilder(NotePreparationSupport notePreparationSupport) {
        this.notePreparationSupport = notePreparationSupport;
    }

    protected final NotePreparationSupport notes() {
        return notePreparationSupport;
    }

    protected final List<StructuredNoteContext> structuredNotes(Map<String, Object> standardizedDayFacts) {
        List<StructuredNoteContext> contexts = new ArrayList<>();
        for (Map<String, Object> note : notePreparationSupport.readStructuredNotes(standardizedDayFacts)) {
            String noteType = notePreparationSupport.valueAsString(note.get("note_type"));
            String timestamp = notePreparationSupport.valueAsString(note.get("timestamp"));
            contexts.add(new StructuredNoteContext(
                    noteType,
                    notePreparationSupport.buildNoteRef(noteType, timestamp),
                    notePreparationSupport.toStructuredNode(note.get("structured"))
            ));
        }
        return contexts;
    }

    protected record StructuredNoteContext(
            String noteType,
            String noteRef,
            JsonNode structuredNode
    ) {
    }
}
