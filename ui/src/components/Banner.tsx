import { useSession } from '../state/session';

/** Red banner on every screen while the environment label is prod. */
export function Banner() {
  const { env } = useSession();
  if (env !== 'prod') return null;
  return (
    <div className="banner-prod" role="alert">
      PROD: this label is refused. Data Forge will not connect to a production database from this screen.
    </div>
  );
}
