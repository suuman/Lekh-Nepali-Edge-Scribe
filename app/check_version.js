const { exec } = require('child_process');
const https = require('https');
https.get('https://raw.githubusercontent.com/google-ai-edge/gallery/main/Android/gradle/libs.versions.toml', (res) => {
    let rawData = '';
    res.on('data', (chunk) => { rawData += chunk; });
    res.on('end', () => {
        try {
            console.log(rawData.split('\n').filter(line => line.includes('litert') || line.includes('genai')).join('\n'));
        } catch (e) {
            console.error(e.message);
        }
    });
});
