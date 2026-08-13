# WirelessAdb-LSPosed

**Idioma:** [简体中文](README.md) · [Português (Brasil)](README.pt-BR.md) · [English](README.en.md) · [Español](README.es.md)

**Módulo LSPosed**: ativa automaticamente o ADB sem fio após o primeiro desbloqueio depois da inicialização, com o endereço e os registros disponíveis na tela de status.

Nome do pacote: `dev.wirelessadb.autostart`
Versão atual: `1.0.18`

<p align="center">
  <img src="docs/screenshot-pt-BR.png" alt="Tela de status do início automático do ADB sem fio em português do Brasil" width="360" />
</p>

## Recursos

- **Modo TLS**: Depuração sem fio do sistema (`adb_wifi_enabled`), porta aleatória e pareamento obrigatório
- **Modo TCP**: equivalente a `adb tcpip <port>`, com `5555` como padrão; o computador pode conectar diretamente com `adb connect IP:5555`
- Monitora a desativação da Depuração sem fio (somente TLS), a recuperação do Wi-Fi e o desbloqueio da tela, reativando-a quando necessário
- Copia automaticamente o endereço para a área de transferência quando ele muda (aguarda que o método de entrada WeChat esteja pronto para colar entre dispositivos)
- Ignora endereços virtuais de VPN `172.19.*` ao obter o endereço IP
- Grava registros do início do sistema em `Settings.Global`, permitindo visualizá-los na tela de status

## Instalação

1. Instale o APK de Release
2. Ative este módulo no **LSPosed**
3. Selecione **Framework do sistema** (`android`) como escopo
4. Reinicie o celular e conclua o primeiro desbloqueio

> Use somente em redes locais confiáveis. Alternar para TCP reinicia o `adbd`, o que pode desconectar a sessão ADB atual.

## Compilação

```bash
./gradlew :app:assembleRelease
```

Artefato: `app/build/outputs/apk/release/app-release.apk`

É necessário configurar o Android SDK localmente (`sdk.dir` em `local.properties` ou a variável de ambiente `ANDROID_HOME`).

## Licença

MIT
