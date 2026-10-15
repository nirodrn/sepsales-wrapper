import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const wrapperDir = path.resolve(scriptDir, '..');
const source = path.join(wrapperDir, 'capacitor.uat.config.json');
const destination = path.join(wrapperDir, 'android', 'app', 'src', 'main', 'assets', 'capacitor.config.json');

if (!fs.existsSync(source)) throw new Error(`Missing UAT Capacitor config: ${source}`);
fs.mkdirSync(path.dirname(destination), { recursive: true });
fs.copyFileSync(source, destination);

const copied = JSON.parse(fs.readFileSync(destination, 'utf8'));
if (copied.server?.url) throw new Error('UAT build aborted: server.url must not exist in the bundled UAT config.');
if (copied.appId !== 'com.sewanagala.sepsale.uat') throw new Error('UAT build aborted: unexpected application id.');
console.log('Applied bundled UAT config (no production server.url).');

const googleServicesSource = path.join(wrapperDir, 'android', 'app', 'google-services.json');
const googleServicesDestination = path.join(wrapperDir, 'android', 'app', 'src', 'uat', 'google-services.json');
if (fs.existsSync(googleServicesSource)) {
  const googleServices = JSON.parse(fs.readFileSync(googleServicesSource, 'utf8'));
  const clients = Array.isArray(googleServices.client) ? googleServices.client : [];
  const baseClient = clients.find((client) => client?.client_info?.android_client_info?.package_name === 'com.sewanagala.sepsale') || clients[0];
  if (baseClient) {
    const uatClient = structuredClone(baseClient);
    uatClient.client_info.android_client_info.package_name = 'com.sewanagala.sepsale.uat';
    googleServices.client = [uatClient];
    fs.mkdirSync(path.dirname(googleServicesDestination), { recursive: true });
    fs.writeFileSync(googleServicesDestination, `${JSON.stringify(googleServices, null, 2)}\n`, 'utf8');
    console.log('Prepared variant-scoped Firebase config for com.sewanagala.sepsale.uat.');
  }
}
