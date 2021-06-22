import { makeStyles } from '@material-ui/core/styles';
import {
  GREY_COLOR,
  LIGHT_BLUE_COLOR2,
  LIGHT_GREY_COLOR3,
} from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  chatOutput: {
    display: 'flex',
    margin: '0 0 10px',
  },
  outputText: {
    color: GREY_COLOR,
    padding: '5px 10px',
    margin: 0,
    backgroundColor: LIGHT_GREY_COLOR3,
    borderRadius: '15px',
    overflow: 'hidden',
  },
  chatInput: {
    display: 'flex',
    alignSelf: 'flex-end',
    marginBottom: '20px',
    marginTop: '10px',
  },
  inputText: {
    backgroundColor: LIGHT_BLUE_COLOR2,
  },
});

export default useStyles;
