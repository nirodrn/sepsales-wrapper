import { S3Client, PutObjectCommand } from '@aws-sdk/client-s3';
import { Upload } from '@aws-sdk/lib-storage';
import fs from 'fs';
import path from 'path';
import dotenv from 'dotenv';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

dotenv.config({ path: path.resolve(__dirname, '../.env') });

const accountId = process.env.R2_ACCOUNT_ID;
const accessKeyId = process.env.R2_ACCESS_KEY_ID;
const secretAccessKey = process.env.R2_SECRET_ACCESS_KEY;
const bucketName = process.env.R2_BUCKET_NAME || 'sep-sales-apks';
const publicUrl = (process.env.R2_PUBLIC_URL || 'https://pub-ea8812066a2f45849522e9ddf17296fb.r2.dev').replace(/\/$/, '');

if (!accountId || !accessKeyId || !secretAccessKey) {
  console.error('❌ Missing R2 credentials in .env file!');
  process.exit(1);
}

const R2 = new S3Client({
  region: 'auto',
  endpoint: `https://${accountId}.r2.cloudflarestorage.com`,
  credentials: {
    accessKeyId,
    secretAccessKey
  },
  forcePathStyle: true,
  requestHandler: {
    connectionTimeout: 60000,
    socketTimeout: 300000
  }
});

const pkgPath = path.resolve(__dirname, '../package.json');
const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
const version = pkg.version || '12.3.1';

const versionParts = version.split('.').map(n => parseInt(n, 10) || 0);
const versionCode = (versionParts[0] * 10000) + (versionParts[1] * 100) + (versionParts[2] || 0);

const apkCandidates = [
  path.resolve(__dirname, '../android/app/build/outputs/apk/production/debug/app-production-debug.apk'),
  path.resolve(__dirname, '../android/app/build/outputs/apk/debug/app-debug.apk')
];
const sourceApkPath = apkCandidates.find(candidate => fs.existsSync(candidate));

if (!sourceApkPath) {
  console.error('❌ APK not found!');
  process.exit(1);
}

async function uploadRelease() {
  console.log(`🚀 Starting release upload for v${version}...`);
  const versionedApkName = `SEP-Sales-v${version}.apk`;
  const defaultApkName = `app-release.apk`;

  const fileStat = fs.statSync(sourceApkPath);
  console.log(`📊 APK Size: ${(fileStat.size / (1024 * 1024)).toFixed(2)} MB`);

  console.log(`⏳ [1/3] Uploading ${versionedApkName}...`);
  const uploadApk = (key, cacheControl) => new Upload({
    client: R2,
    params: {
      Bucket: bucketName,
      Key: key,
      Body: fs.createReadStream(sourceApkPath),
      ContentLength: fileStat.size,
      ContentType: 'application/vnd.android.package-archive',
      CacheControl: cacheControl
    },
    partSize: 8 * 1024 * 1024,
    queueSize: 2
  }).done();

  await uploadApk(versionedApkName, 'public, max-age=31536000, immutable');
  console.log(`\n✅ [1/3] Uploaded ${versionedApkName}`);

  console.log(`⏳ [2/3] Uploading ${defaultApkName}...`);
  await uploadApk(defaultApkName, 'no-cache, no-store, must-revalidate');
  console.log(`\n✅ [2/3] Uploaded ${defaultApkName}`);

  const manifestData = {
    version: version,
    versionCode: versionCode,
    apkName: versionedApkName,
    apkUrl: `${publicUrl}/${versionedApkName}`,
    fallbackApkUrl: `${publicUrl}/${defaultApkName}`,
    releaseNotes: `Automated release for version ${version}`,
    timestamp: Date.now(),
    updatedAt: new Date().toISOString()
  };

  if (process.argv.includes('--manifest-only')) {
    console.log(`⏳ Uploading version.json only...`);
    await R2.send(new PutObjectCommand({
      Bucket: bucketName,
      Key: 'version.json',
      Body: JSON.stringify(manifestData, null, 2),
      ContentType: 'application/json',
      CacheControl: 'no-cache, no-store, must-revalidate'
    }));
    console.log(`✅ version.json now points to v${version}`);
    return;
  }

  console.log(`⏳ [3/3] Uploading version.json...`);
  await R2.send(
    new PutObjectCommand({
      Bucket: bucketName,
      Key: 'version.json',
      Body: JSON.stringify(manifestData, null, 2),
      ContentType: 'application/json',
      CacheControl: 'no-cache, no-store, must-revalidate'
    })
  );
  console.log(`✅ [3/3] Uploaded version.json`);
  console.log(`\n🎉 Release v${version} complete!`);
}

uploadRelease().catch((err) => {
  console.error('❌ Upload error:', err);
  process.exit(1);
});
