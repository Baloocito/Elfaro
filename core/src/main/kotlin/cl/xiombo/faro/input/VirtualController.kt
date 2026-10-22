package cl.xiombo.faro.input

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.controllers.Controller
import com.badlogic.gdx.controllers.ControllerListener
import com.badlogic.gdx.controllers.ControllerMapping
import com.badlogic.gdx.controllers.ControllerPowerLevel

/**
 * Mando virtual de depuración (F1 + F7 en PC): sirve para probar la configuración de botones
 * sin un mando real. Las teclas 1–0 hacen de botones:
 * 1 A · 2 B · 3 X · 4 Y · 5 LB · 6 RB · 7 LT · 8 RT · 9 L3 · 0 R3 · Tab Select.
 */
class VirtualController : Controller {
    private val keys = intArrayOf(
        Input.Keys.NUM_1, Input.Keys.NUM_2, Input.Keys.NUM_3, Input.Keys.NUM_4,
        Input.Keys.NUM_5, Input.Keys.NUM_6, Input.Keys.NUM_7, Input.Keys.NUM_8,
        Input.Keys.NUM_9, Input.Keys.NUM_0, Input.Keys.TAB,
    )
    private val map = ControllerMapping(
        0, 1, 2, 3,          // ejes: izq X/Y, der X/Y
        0, 1, 2, 3,          // A B X Y
        10, 11,              // Select, Start
        4, 6, 5, 7,          // LB, LT, RB, RT
        8, 9,                // L3, R3
        12, 13, 14, 15,      // cruceta
    )

    override fun getButton(buttonCode: Int): Boolean =
        buttonCode in keys.indices && Gdx.input.isKeyPressed(keys[buttonCode])

    override fun getAxis(axisCode: Int): Float = 0f
    override fun getName(): String = "Mando virtual (teclas 1-0)"
    override fun getUniqueId(): String = "virtual"
    override fun getMinButtonIndex(): Int = 0
    override fun getMaxButtonIndex(): Int = 15
    override fun getAxisCount(): Int = 4
    override fun isConnected(): Boolean = true
    override fun canVibrate(): Boolean = false
    override fun isVibrating(): Boolean = false
    override fun startVibration(duration: Int, strength: Float) {}
    override fun cancelVibration() {}
    override fun supportsPlayerIndex(): Boolean = false
    override fun getPlayerIndex(): Int = Controller.PLAYER_IDX_UNSET
    override fun setPlayerIndex(index: Int) {}
    override fun getMapping(): ControllerMapping = map
    override fun getPowerLevel(): ControllerPowerLevel = ControllerPowerLevel.POWER_UNKNOWN
    override fun addListener(listener: ControllerListener?) {}
    override fun removeListener(listener: ControllerListener?) {}
}
