package b;

import a.A;

public class B {
    public String shout(String s) {
        return new A().shout(s) + "!";
    }
}
