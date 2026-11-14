import { useSession } from '../state/session';

/** Red banner on every screen while the environment label is prod. */
export function Banner() {
  const { env } = useSession();
  if (env !== 'prod') return null;
  return (
    <div className="banner-prod" role="alert">
      PROD label selected. Data Forge refuses to connect with it and will not work on a production database from this screen.
    </div>
  );
}
