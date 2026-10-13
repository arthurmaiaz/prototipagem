# Bibliotecas locais

## eNBioBSP SDK for Android (Nitgen)

O app usa o SDK da Nitgen para capturar a digital no leitor Hamster DX
(`NITGEN FDU06`, USB `0a86:0100`). O SDK é proprietário, então **não vai
para o repositório** (este repositório é público): o `.gitignore` ignora
`app/libs/*.aar`. Sem ele o build para com uma mensagem apontando para cá.

Para compilar:

1. Peça o pacote **"[BETA] eNBioBSP SDK for Android v2.0.0.10(18)"** a quem
   já tem (Arthur), ou à Nitgen.
2. Copie `lib/NBioBSPJNI_v2.0.0.10(18).aar` do pacote para esta pasta com o
   nome **`nbiobspjni-2.0.0.10.aar`**.
3. No `local.properties` (também fora do Git), adicione o código serial do
   SDK, o mesmo usado no app de exemplo que vem no pacote
   (`sample/.../MainActivityPresenter.java`, no `new NBioBSPJNI(...)`):
   ```properties
   nitgen.serial=<código serial>
   ```

Para testar: `adb shell am start -n br.unifor.cct/.ui.TesteCapturaActivity`.
