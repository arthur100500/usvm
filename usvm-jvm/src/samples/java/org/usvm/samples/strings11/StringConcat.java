package org.usvm.samples.strings11;


import java.util.*;

import static org.usvm.api.mock.UMockKt.assume;

public class StringConcat {
    public static class Test {
        public int x;

        @Override
        public String toString() {
            if (x == 42) {
                throw new IllegalArgumentException();
            }
            return "x = " + x;
        }
    }

    String str;
    public String concatArguments(String a, String b, String c) {
        return a + b + c;
    }

    public int concatWithConstants(String a) {
        String res = '<' + a + '>';

        if (res.equals("<head>")) {
            return 1;
        }

        if (res.equals("<body>")) {
            return 2;
        }

        if (a == null) {
            return 3;
        }

        return 4;
    }

    public String concatWithPrimitives(String a) {
        return a + '#' + 42 + 53.0;
    }

    public String exceptionInToString(Test t) {
        return "Test: " + t + "!";
    }

    public String concatWithField(String a) {
        return a + str + '#';
    }

    public int concatWithPrimitiveWrappers(Integer b, char c) {
        String res = "" + b + c;

        if (res.endsWith("42")) {
            return 1;
        }
        return 2;
    }

    public int sameConcat(String a, String b) {
        assume(a != null && b != null);

        String res1 = '!' + a + '#';
        String res2 = '!' + b + '#';

        if (res1.equals(res2)) {
            return 0;
        } else {
            return 1;
        }
    }

    public String concatStrangeSymbols() {
        return "\u0000" + '#' + '\u0001' + "!\u0002" + "@\u0012\t";
    }

    public static boolean checkStringBuilder(String s, char c, int i) {
        if (s == null || s.length() > 10 || c > 0xFF)
            return true;

        StringBuilder sb = new StringBuilder();
        sb.append(s);
        String a = "str" + c;
        String b = a + i;
        sb.append(a);
//        String res = sb.toString();
        int pos = s.length() + "str".length();
        if (sb.charAt(pos) != c)
            return false;

//        if (i >= 0 && i < 128 && res.charAt(pos + 1) != i)
//            return false;

        return true;
    }

    public static void wip1(HashMap<String, String> map) {
        map.clear();
        map.put("str", "def");
    }

    public static void wip2(HashMap<String, String> map) {
        map.clear();
        map.put("str", "qwe");
    }

    public static void concretize() { }

    public static boolean wip(int i) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put("str", "abc");
        if (i > 0) {
            concretize();
            if (!map.get("str").equals("abc"))
                return false;
            wip1(map);
            if (!map.get("str").equals("def"))
                return false;
        } else {
            concretize();
            if (!map.get("str").equals("abc"))
                return false;
            wip2(map);
            if (!map.get("str").equals("qwe"))
                return false;
        }
        return true;
    }

    public static boolean wip3(int i) {
        if (!String.format("GMT%+d:00", i).contains(Integer.toString(i))) {
            return false;
        }

        return true;
    }

    public static class Attr {
        public String name;

        Attr(String name) {
            this.name = name;
        }
    }

    public static final class AttrRepo {

        private final List<String> attrsNames = new ArrayList<String>(10);
        private final List<Attr> attrs = new ArrayList<Attr>(10);
        Attr getAttr(String name) {
            for (int i = 0 ; i < attrsNames.size() ; i++) {
                if (attrsNames.get(i).equals(name)) {
                    return attrs.get(i);
                }
            }
            Attr attr = new Attr(name);
            attrsNames.add(name);
            attrs.add(attr);
            return attr;
        }
    }

    public static class AttrNames {
        private static final AttrRepo repo = new AttrRepo();
        public static Attr getAttr(String name) {
            return repo.getAttr(name);
        }
    }

    public static void F(Attr attr) {

    }

    public static boolean wip4(int i) {
        if (i > 0) {
            Attr a = AttrNames.getAttr("abc");
            Attr d = AttrNames.getAttr("cbd");
            F(a);
            if (i > 5) {
                Attr c = AttrNames.getAttr("abc");
                Attr b = AttrNames.getAttr("cbd");
                if (c != a || b != d)
                    return false;
                return true;
            } else {
                Attr b = AttrNames.getAttr("cbd");
                Attr c = AttrNames.getAttr("abc");
                if (c != a || b != d)
                    return false;
                return true;
            }
        } else {
            Attr a = AttrNames.getAttr("cbd");
            Attr d = AttrNames.getAttr("abc");
            F(a);
            if (i < -5) {
                Attr b = AttrNames.getAttr("abc");
                Attr c = AttrNames.getAttr("cbd");
                if (c != a || b != d)
                    return false;
                return true;
            } else {
                Attr c = AttrNames.getAttr("cbd");
                Attr b = AttrNames.getAttr("abc");
                if (c != a || b != d)
                    return false;
                return true;
            }
        }
    }

    public static boolean wip5(int i) {
        Attr[] attrs = new Attr[] {new Attr("abc"), new Attr("cbd"), new Attr("def"), new Attr("ghi"), new Attr("jkl"), new Attr("mno")};
        Attr second = attrs[1];
        if (i >= 0 && i < attrs.length) {
            Attr attr = attrs[i];
            attr.name = "kek";
            if (i == 1 && (!attrs[1].name.equals("kek") || !second.name.equals("kek")))
                return false;
        }

        return true;
    }

    static class Kekw {
        public int x = 0;

        public void F() {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("str", "abc");
            map.put("kek", "def");
            map.put("123", "qwe");
            map.forEach(this::G);
        }

        public void G(Object key, Object value) {
            x++;
        }
    }

    public static boolean kek(int i) {
        Kekw kekw = new Kekw();
        kekw.F();
        if (kekw.x == 3)
            return true;
        return false;
    }
}
