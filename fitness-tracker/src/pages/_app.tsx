import type { AppType } from "next/app";
import Head from "next/head";

import { api } from "~/utils/api";
import "~/styles/globals.css";

const App: AppType = ({ Component, pageProps }) => {
  return (
    <>
      <Head>
        <title>Fitness Tracker</title>
        <meta name="description" content="Track workouts, exercises and sets." />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
      </Head>
      <Component {...pageProps} />
    </>
  );
};

export default api.withTRPC(App);
