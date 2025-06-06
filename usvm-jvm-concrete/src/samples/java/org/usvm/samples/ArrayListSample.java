package org.usvm.samples;

import org.usvm.api.Engine;

import java.util.ArrayList;
import java.util.HashSet;

public class ArrayListSample {
    @SuppressWarnings("unchecked")
    public static boolean hasThree(int r) {
        HashSet<Integer> input = (HashSet<Integer>) Engine.makeSymbolic(HashSet.class);
        Engine.assume(input != null);
        Engine.assume(input.size() < 4);
        for (Integer value : input) {
            if (value == 3) return true;
        }
        return false;
    }
}
