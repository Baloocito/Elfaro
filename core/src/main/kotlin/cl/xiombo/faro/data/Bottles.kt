package cl.xiombo.faro.data

/**
 * Botellas con mensaje (§9): 40 notas sobre Puerto Velatorio antes del hundimiento. Aparecen como
 * máximo una por run fuera de la Sala de lectura (que siempre tiene una). Leerlas todas abre el final B.
 *
 * Solo Latin-1: sin "—", "…" ni comillas « ».
 */
object Bottles {
    class Bottle(val id: String, val from: String, val text: String)

    val all = listOf(
        Bottle("b01", "Lista de la compra", "Pan, sal, aceite de lámpara. Mucho aceite de lámpara. El Farero paga bien y no pregunta el precio."),
        Bottle("b02", "Nota de un pescador", "Hoy la red salió vacía otra vez. Mi padre dice que los peces se van cuando la luz del faro gira demasiado rápido."),
        Bottle("b03", "Carta de una madre", "Hija: no te acerques a la galería del faro de noche. No es por el frío. Es por lo que se ve desde ahí."),
        Bottle("b04", "Aviso del puerto", "Se prohíbe faenar en la fosa. Tres barcas no volvieron este mes. El Ayuntamiento no responde de lo que suba."),
        Bottle("b05", "Diario de Ondina", "Hoy canté en la lonja y el agua se quedó quieta. Juro que se quedó quieta, como si escuchara."),
        Bottle("b06", "Recibo de la cerería", "Doscientas velas para la catedral. El obispo pide que sean de cera blanca, sin mecha negra. Dice que la negra atrae."),
        Bottle("b07", "Nota sin firma", "La lámpara no ilumina el mar. Ilumina hacia abajo. Fíjate la próxima vez que gire."),
        Bottle("b08", "Cuaderno de un niño", "Hoy vi una luz debajo del agua. Era más grande que el barco de mi tío. Mi maestra dice que fue la luna."),
        Bottle("b09", "Carta de Amparo", "Señor Farero: le he subido el aceite y las mechas. Le dejo también pan. Coma algo, por favor. No sale de la lámpara desde el martes."),
        Bottle("b10", "Acta del concejo", "Se aprueba duplicar la potencia de la lámpara para la temporada de tormentas. Voto en contra: el Farero."),
        Bottle("b11", "Página arrancada", "Cuanto más brilla, más sube. Cuanto más sube, más hay que brillar. Así no se acaba nunca."),
        Bottle("b12", "Nota del Cartógrafo", "La fosa era de 300 brazas cuando empecé el mapa. Ahora es de 280. No es que el fondo suba. Es que algo crece."),
        Bottle("b13", "Postal", "Querida prima: Puerto Velatorio es precioso. El faro gira toda la noche y nadie duerme a oscuras. Es casi demasiada luz."),
        Bottle("b14", "Sermón del obispo", "Hermanos: la luz es un regalo. Pero hay regalos que se dan para que el otro deje de pedir."),
        Bottle("b15", "Nota de la cerería", "El obispo devolvió las velas. Dice que ya no hacen falta tantas. Que la catedral reza mejor a oscuras."),
        Bottle("b16", "Diario de Ondina", "El Farero vino a escucharme cantar. Al terminar me preguntó si el agua se quedaba quieta también con la luz apagada."),
        Bottle("b17", "Mensaje de un marinero", "Si alguien lee esto: no fue la tormenta. La barca no se hundió. La levantaron."),
        Bottle("b18", "Lista de turnos del faro", "Lunes: Farero. Martes: Farero. Miércoles: Farero. Amparo, si lees esto, no subas. Yo me encargo."),
        Bottle("b19", "Carta del Farero", "Teodoro: le pido otra vez que rece mirando al agua. No por nosotros. Para que sepa que la estamos viendo."),
        Bottle("b20", "Nota de un copista", "Copié tres veces el mismo registro de nacimientos y las tres veces salió un nombre más. No sé de quién es."),
        Bottle("b21", "Recorte de periódico", "PUERTO VELATORIO, LA CIUDAD QUE NUNCA DUERME. El nuevo faro alumbra hasta las islas del sur."),
        Bottle("b22", "Diario de Isla", "El Farero me dejó tocar la lámpara hoy. Quemaba, pero no la mano. Quemaba por dentro, como cuando tienes miedo."),
        Bottle("b23", "Nota del bibliotecario", "Faltan las cartas de la fosa. El Cartógrafo dice que las quemó él. No le creo: él jamás quemaría un mapa."),
        Bottle("b24", "Carta de amor", "Te espero en el muelle cuando apaguen el faro. Ya sé que nunca lo apagan. Por eso te digo que te espero siempre."),
        Bottle("b25", "Informe del médico", "Tres casos más de la enfermedad de la luz: no duermen, no comen, solo miran hacia el faro. Recomiendo cortinas."),
        Bottle("b26", "Diario de Ondina", "Ayer el agua no se quedó quieta. Subió un palmo mientras cantaba. Creo que ya no me escucha. Creo que me contesta."),
        Bottle("b27", "Nota del Farero", "Si la apago, la ciudad se hunde. Si no la apago, la ciudad se come. No hay una tercera opción. La estoy buscando."),
        Bottle("b28", "Mensaje rayado", "LA LUZ NO LA ESPANTA. LA LUZ LA ALIMENTA. LA LUZ NO LA ESPANTA. LA LUZ LA ALIMENTA."),
        Bottle("b29", "Carta de Amparo", "Señor Farero: he visto lo que hay en el fondo. Si usted no la enciende, la enciendo yo. La ciudad no puede quedarse a oscuras."),
        Bottle("b30", "Nota del Farero", "Encerré a Amparo en la celda del muelle. Le dejé comida para un mes. Que me perdone si puede: era ella o todos."),
        Bottle("b31", "Último registro del puerto", "Esta noche la lámpara se ha apagado a las doce y diez. El agua empezó a subir a las doce y once."),
        Bottle("b32", "Diario del Cartógrafo", "Terminé el mapa de la fosa. Tiene la forma de un ojo cerrado. Lo dibujé otra vez desde arriba. Seguía siendo un ojo."),
        Bottle("b33", "Nota de un monaguillo", "El señor obispo dice que no hay que tener miedo. Pero esta noche ha cerrado la catedral con llave por dentro."),
        Bottle("b34", "Carta a Isla", "Isla: si un día bajas, baja velando. Lo que hay allí no ve en la oscuridad. Ve la luz. Firmado: el viejo."),
        Bottle("b35", "Página de un cuaderno de faro", "Regla primera: la lámpara nunca se apaga. Regla segunda (añadida a mano): salvo que sea la única forma."),
        Bottle("b36", "Nota en una caja de mechas", "Estas mechas no prenden. El Farero las cambió todas por mechas mojadas. ¿Para qué querría un farero mechas que no prenden?"),
        Bottle("b37", "Diario de Ondina", "Si alguien encuentra esto: no estoy muerta. Estoy cantando. Abajo el agua se queda quieta otra vez."),
        Bottle("b38", "Carta del obispo", "Ahora lo entiendo, viejo amigo. No rezábamos para que la luz durara. Rezábamos para que algún día alguien se atreviera a apagarla."),
        Bottle("b39", "Nota del Farero", "Veinte años después alguien bajará a encenderla otra vez. Ojalá sea alguien que sepa velar."),
        Bottle("b40", "Sin remitente", "Sé que la has leído toda. Sé que bajaste. Ahora ya sabes lo mismo que él. Decide mejor que nosotros."),
    )

    fun get(id: String) = all.firstOrNull { it.id == id }
}
