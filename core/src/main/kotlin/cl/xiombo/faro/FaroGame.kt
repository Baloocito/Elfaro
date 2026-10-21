package cl.xiombo.faro

import cl.xiombo.faro.data.EnemyDefs
import cl.xiombo.faro.data.RoomTemplates
import cl.xiombo.faro.screen.RunScreen
import ktx.app.KtxGame
import ktx.app.KtxScreen

class FaroGame : KtxGame<KtxScreen>() {
    override fun create() {
        EnemyDefs.load()
        RoomTemplates.load()
        addScreen(RunScreen())
        setScreen<RunScreen>()
    }
}
