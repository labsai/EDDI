import { makeStyles } from '@material-ui/core/styles';

const useStyles = makeStyles({
  body: {
    overflow: 'scroll',
  },
  eddiLogo: {
    display: 'block',
    marginTop: '20px',
    marginBottom: '20px',
    width: '100px',
  },
  loadingWrapper: {
    alignItems: 'center',
    display: 'flex',
    flex: 1,
    height: '100vh',
    justifyContent: 'center',
  },
});

export default useStyles;
