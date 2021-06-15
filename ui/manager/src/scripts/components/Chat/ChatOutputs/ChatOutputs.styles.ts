import { makeStyles } from '@material-ui/core/styles';
import { GREY_COLOR } from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  chatOutputs: {
    display: 'flex',
    flexDirection: 'column',
    width: '100%',
    marginTop: '10px',
    justifyContent: 'flex-start',
    alignItems: 'flex-start',
  },
});

export default useStyles;
