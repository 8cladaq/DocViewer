package com.example.doclens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Structural edits applied when the document is saved. */
public final class EditActions {

    public final List<Integer> rotations = new ArrayList<>();
    public final List<Integer> deletions = new ArrayList<>();
    public final List<int[]> moves = new ArrayList<>();
    public final Map<String, String> formValues = new HashMap<>();
    public byte[] mergeBytes;

    public void clear() {
        rotations.clear();
        deletions.clear();
        moves.clear();
        formValues.clear();
        mergeBytes = null;
    }
}
