package cl.xiombo.faro

import cl.xiombo.faro.data.EnemyDefs
import cl.xiombo.faro.data.Meta
import cl.xiombo.faro.data.RoomTemplates
import cl.xiombo.faro.screen.RunScreen
import ktx.app.KtxGame
import ktx.app.KtxScreen

class FaroGame : KtxGame<KtxScreen>() {
    override fun create() {
        EnemyDefs.load()
        RoomTemplates.load()
        Meta.load()
        cl.xiombo.faro.data.Settings.load()
        cl.xiombo.faro.data.RunLog.load()
        addScreen(RunScreen())
        setScreen<RunScreen>()
    }
}
