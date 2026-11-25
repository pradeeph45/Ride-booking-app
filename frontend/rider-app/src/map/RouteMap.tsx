import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { useEffect } from 'react';
import { MapContainer, Marker, Polyline, TileLayer, useMap, useMapEvents } from 'react-leaflet';
import { DEFAULT_CENTER, type LatLng } from './geo';

export type PinKind = 'pickup' | 'drop';

// Pins are drawn with CSS (see styles.css) so there are no image files for the bundler to lose.
const ICONS: Record<PinKind, L.DivIcon> = {
  pickup: L.divIcon({ className: 'pin pin-pickup', html: '<span></span>', iconSize: [30, 30], iconAnchor: [15, 15] }),
  drop: L.divIcon({ className: 'pin pin-drop', html: '<span></span>', iconSize: [30, 30], iconAnchor: [15, 15] }),
};

interface Props {
  pickup: LatLng | null;
  drop: LatLng | null;
  /** When true, tapping the map sets the {@code target} pin and pins can be dragged. */
  interactive?: boolean;
  target?: PinKind;
  onPick?: (kind: PinKind, point: LatLng) => void;
  height?: number;
  label?: string;
}

function TapToPlace({ target, onPick }: { target: PinKind; onPick: (kind: PinKind, p: LatLng) => void }) {
  useMapEvents({ click: (e) => onPick(target, { lat: e.latlng.lat, lng: e.latlng.lng }) });
  return null;
}

/** Keeps the view on whatever is pinned: both points fitted with some padding, or a single point centred. */
function FitToPins({ pickup, drop }: { pickup: LatLng | null; drop: LatLng | null }) {
  const map = useMap();
  useEffect(() => {
    if (pickup && drop) {
      const pad = map.getSize().y < 220 ? 22 : 44; // small trip maps need tighter padding or the pins end up zoomed far out
      map.fitBounds(L.latLngBounds([pickup, drop]), { padding: [pad, pad], maxZoom: 16 });
    } else if (pickup || drop) {
      map.setView((pickup ?? drop)!, Math.max(map.getZoom(), 14));
    }
  }, [map, pickup?.lat, pickup?.lng, drop?.lat, drop?.lng]); // eslint-disable-line react-hooks/exhaustive-deps
  return null;
}

/**
 * OpenStreetMap map showing the pickup and drop pins and the line between them. In interactive mode a tap
 * places the selected pin and pins can be dragged; otherwise it is a compact read-only view for a trip.
 * Map tiles come from OpenStreetMap's public servers: fine for development and light use, but switch to a
 * tile provider or your own tile server before real traffic (their usage policy forbids heavy use).
 */
export default function RouteMap({ pickup, drop, interactive = false, target = 'pickup', onPick, height = 160, label = 'Route map' }: Props) {
  const center = pickup ?? drop ?? DEFAULT_CENTER;
  const drag = (kind: PinKind) => ({
    dragend: (e: L.LeafletEvent) => {
      const ll = (e.target as L.Marker).getLatLng();
      onPick?.(kind, { lat: ll.lat, lng: ll.lng });
    },
  });

  return (
    <div className="map" style={{ height }} role="region" aria-label={label}>
      <MapContainer
        center={center}
        zoom={13}
        scrollWheelZoom={interactive}
        zoomControl={interactive}
        dragging={interactive || height >= 220}
        doubleClickZoom={interactive}
        style={{ height: '100%', width: '100%' }}
      >
        <TileLayer
          url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          maxZoom={19}
        />
        <FitToPins pickup={pickup} drop={drop} />
        {interactive && onPick && <TapToPlace target={target} onPick={onPick} />}
        {pickup && drop && <Polyline positions={[pickup, drop]} pathOptions={{ color: '#4f46e5', weight: 4, dashArray: '8 8', opacity: 0.85 }} />}
        {pickup && <Marker position={pickup} icon={ICONS.pickup} draggable={interactive} eventHandlers={interactive ? drag('pickup') : undefined} />}
        {drop && <Marker position={drop} icon={ICONS.drop} draggable={interactive} eventHandlers={interactive ? drag('drop') : undefined} />}
      </MapContainer>
    </div>
  );
}
