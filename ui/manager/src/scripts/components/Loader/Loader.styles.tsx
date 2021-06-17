import { makeStyles } from '@material-ui/core/styles';

const useStyles = makeStyles({
  loaderContainer: {
    backgroundColor: 'rgba(255,255,255,0.5)',
    position: 'fixed',
    top: 0,
    left: 0,
    width: '100vw',
    height: '100vh',
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
  },
});

export default useStyles;
