abstract class  IX  {
    void foo(){}
}

class XXC extends IX {
    @Override
    void foo() {
        <caret><selection>super.foo();    //To change body of overridden methods use File | Settings | File Templates.</selection>
    }
}
