import createQr from 'qrcode-generator';
import { View } from 'react-native';

/** A QR code drawn with plain Views (no SVG dependency). Dark modules on white, with a quiet zone. */
export function QrCode({ value, size = 220 }: { value: string; size?: number }) {
  const qr = createQr(0, 'M');
  qr.addData(value);
  qr.make();
  const count = qr.getModuleCount();
  const cell = Math.floor(size / (count + 8));
  const rows = Array.from({ length: count }, (_, r) => r);
  return (
    <View
      accessibilityLabel="Device approval code"
      style={{ alignSelf: 'center', backgroundColor: '#fff', padding: cell * 4 }}>
      {rows.map((r) => (
        <View key={r} style={{ flexDirection: 'row' }}>
          {rows.map((c) => (
            <View key={c} style={{ width: cell, height: cell, backgroundColor: qr.isDark(r, c) ? '#000' : '#fff' }} />
          ))}
        </View>
      ))}
    </View>
  );
}
