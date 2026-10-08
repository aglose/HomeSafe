package com.meticulouscreations.homesafe.weather.data

/*
 * The colour tables the radar tiles are drawn in, as published by their providers, so a tile's
 * colours can be turned back into what they stand for (see RadarDecoder). Generated from:
 *
 * - N0Q and LCREF: https://mesonet.agron.iastate.edu/GIS/rasters.php?rid=2 and ?rid=4. 255
 *   entries each, RGB, from -32 dBZ in half-dBZ steps. HRRR's forecast tiles use N0Q; the MRMS
 *   mosaic uses LCREF, which is black (no echo) below about 10 dBZ.
 * - RAINVIEWER: the "Universal Blue" column of https://www.rainviewer.com/files/rainviewer_api_colors_table.csv.
 *   128 entries, RGBA, from -32 dBZ in whole-dBZ steps (the rain half of the table; the snow half
 *   is only used when tiles are asked for with snow coloured apart, which these are not).
 *
 * Each is one long string of two-digit hex channels, which costs nothing to keep and a
 * millisecond to unpack the first time a radar tile arrives.
 */

private const val N0Q_HEX =
    "85718f85728f86738d87758b87768b887789897987897a878a7b858b7d848b7e848c7f828d81808d82808e837e8f847c" +
        "8f857c90877b918879918979928b77938d759691539894579b975b9d9a60a09d64a3a068a5a36da8a671aaa976adac7a" +
        "b0af7eb2b283b7b88cbabb90bdbe94bfc199c2c49dc4c7a2c7caa6cacdaaccd0afd2d4b4cfd2b4c9ccb4c6c9b4c3c7b4" +
        "c0c4b4bdc1b4b9beb4b6bbb4b3b9b4b0b6b4adb3b4aab0b4a4abb4a0a8b49da5b49aa2b497a0b4949db4919ab4949bb5" +
        "9098b48c95b38892b2808cb07c89af7886ae7483ac7080ab6c7daa6779a96376a85f73a75b70a6576da44f67a24b64a1" +
        "4761a0435e9f415b9e4361a24568a6486faa4a76ae4d7db24f84b6518bbb5699c3599fc75ba6cb5eadcf60b4d462bbd8" +
        "65c2dc67c9e06ad0e46fd6e868d6d759d6b352d6a24bd69043d67e3cd66d35d65b11d51811d11710cd1710c81610c416" +
        "0fbc150fb7140eb3140eaf130eab130da6120da2120d9e110c99110c95100c91100b880f0b840e0a800e0a7c0d0a770d" +
        "09730c096f0c096b0b08660b08620a095e09327308467d085b88076f9207849d0698a806adb205c1bd05d6c704ead204" +
        "ffe200ffd800ffd300ffce00ffc900ffc400ffc000ffbb00ffb600ffb100ffac00ffa700ffa200ff9900ff9400ff8f00" +
        "ff8a00ff8500ff8000ff0000f80000f10000ea0000e30000d50000cd0000c60000bf0000b80000b10000aa0000a30000" +
        "9b00009400008d00007f0000780000710000fffffffff5ffffeaffffdfffffd4ffffc9ffffbeffffb3ffff9dffff92ff" +
        "ff75fffc6bfdf960faf656f7f34bf4f040f1ed36efea2bece720e9e10be3b200ffac00fca400f79b00f49300ef8800ea" +
        "8300e87900e27200dd6900db05ecf005ebf005eaf005dde005dce005dbe005cdd005ccd004bdc004bcc004bbc004aeb0" +
        "04adb0049ea0049da0049ca0038e90038d90038c90037e80037d80036f70036e70036d70025f60025e60024f50024e50" +
        "024d50023f40023e40023d40013030012f30012020011f20011e203a67b53a66b53a65b53a64b53a63b53a62b5"

private const val LCREF_HEX =
    "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "000000000000000000000000000000000000a4a4ffa1a1fc9e9ef99a9af69797f29494ef9191ec8e8ee98a8ae68787e3" +
        "8484e08181dc7e7ed97a7ad67777d37474d07171cd6e6ec96a6ac66767c34080ff3e7df93d7af23b76ec3a73e63870df" +
        "366dd9356ad33366cc3263c63060c02e5db92d5ab32b56ac2a53a62850a0264d99254a9323468d22438620408000f900" +
        "00f20000ec0000e60000df0000d90000d30000cc0000c60000c00000b90000b30000ac0000a60000a000009900009300" +
        "008d00008600008000fff900fff200ffec00ffe600ffdf00ffd900ffd300ffcc00ffc600ffc000ffb900ffb300ffac00" +
        "ffa600ffa000ff9900ff9300ff8d00ff8600ff0000fa0000f50000f10000ec0000e70000e30000de0000d90000d40000" +
        "cf0000cb0000c60000c10000bd0000b80000b30000ae0000aa0000a50000ff00fff900f9f200f2ec00ece600e6df00df" +
        "d900d9d300d3cc00ccc600c6c000c0b900b9b300b3ac00aca600a6a000a09900999300938d008d860086fffffff9f9f9" +
        "f2f2f2ececece6e6e6dfdfdfd9d9d9d3d3d3ccccccc6c6c6c0c0c0b9b9b9b3b3b3acacaca6a6a6a0a0a0999999939393" +
        "8d8d8d868686808080808080808080808080808080808080808080808080808080808080808080808080808080808080" +
        "808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080"

private const val RAINVIEWER_HEX =
    "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000000000000000000006361591466635a19" +
        "69665c1e6c685d246f6b5f29726e612e75706234787364397c75653e7f786744827b6949857d6a4e88806c548b826d59" +
        "8e856f5e928871649e93756eaa9e7978b6a97e82c2b4828ccec08796d2c48ba0d6c88faadacc93b4ded097be88ddeeff" +
        "6cd1ebff51c5e8ff36bae5ff1baee2ff00a3e0ff009ad5ff0091caff0088bfff007fb4ff0077aaff0070a3ff00699cff" +
        "006295ff005b8eff005588ff005180ff004e78ff004a70ff004768ffffee00ffffe000ffffd200ffffc500ffffb700ff" +
        "ffaa00ffff9f00ffff9500ffff8b00ffff8100ffff4400fff23600ffe62800ffd91b00ffcd0d00ffc10000ffa80000ff" +
        "8f0000ff760000ff5d0000ffffaaffffff9fffffff95ffffff8bffffff81ffffff77ffffff6cffffff62ffffff58ffff" +
        "ff4effffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff00ff00ff" +
        "00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff" +
        "00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff"

/** One provider's table: [colors] as 0xRRGGBB, the reflectivity each stands for starting at [firstDbz] and rising by [stepDbz]. */
class RadarColorTable(val colors: IntArray, val firstDbz: Float, val stepDbz: Float) {
    fun dbz(index: Int): Float = firstDbz + index * stepDbz
}

internal object RadarPalettes {
    /** The HRRR model's forecast tiles (and the NEXRAD composite). */
    val n0q: RadarColorTable by lazy { RadarColorTable(unpack(N0Q_HEX, 6), -32f, 0.5f) }

    /** The MRMS mosaic. */
    val lcref: RadarColorTable by lazy { RadarColorTable(unpack(LCREF_HEX, 6), -32f, 0.5f) }

    /** RainViewer's one remaining scheme. Entries the provider draws transparent are 0, as "no echo" is. */
    val rainViewer: RadarColorTable by lazy { RadarColorTable(unpack(RAINVIEWER_HEX, 8), -32f, 1f) }

    /** [hex] as colours of [width] digits each; an 8-digit colour whose last two (alpha) are 00 becomes 0. */
    private fun unpack(hex: String, width: Int): IntArray = IntArray(hex.length / width) { i ->
        val at = i * width
        val rgb = hex.substring(at, at + 6).toInt(16)
        if (width == 8 && hex.substring(at + 6, at + 8) == "00") 0 else rgb
    }
}
