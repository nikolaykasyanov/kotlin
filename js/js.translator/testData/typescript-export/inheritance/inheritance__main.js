import { OC, AC, FC } from "./JS_TESTS/index.js";
class Impl extends AC {
    z(z) {
    }
    get acAbstractProp() { return "Impl"; }
    get y() { return true; }
}
function box() {
    const impl = new Impl();
    if (impl.acProp !== "acProp")
        return "Fail 1";
    if (impl.x !== "AC")
        return "Fail 2";
    if (impl.acAbstractProp !== "Impl")
        return "Fail 2.1";
    if (impl.y !== true)
        return "Fail 2.2";
    const oc = new OC(false, "OC");
    if (oc.y !== false)
        return "Fail 3";
    if (oc.acAbstractProp !== "OC")
        return "Fail 4";
    oc.z(10);
    const fc = new FC();
    if (fc.y !== true)
        return "Fail 5";
    if (fc.acAbstractProp !== "FC")
        return "Fail 6";
    fc.z(10);
    return "OK";
}
console.assert(box() === "OK");
