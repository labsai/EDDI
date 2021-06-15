import { makeStyles } from '@material-ui/core/styles';
import {
  GREY_COLOR,
  LIGHT_GREY_COLOR3,
  LIGHT_BLUE_COLOR2,
} from '../../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  chatOutput: {
    display: 'flex',
    margin: '0 10px 10px',
  },
  outputText: {
    color: GREY_COLOR,
    padding: '5px 10px',
    margin: 0,
    backgroundColor: LIGHT_GREY_COLOR3,
    borderRadius: '10px',
    overflow: 'hidden',
  },
  chatInput: {
    display: 'flex',
    alignSelf: 'flex-end',
    marginBottom: '20px',
  },
  inputText: {
    backgroundColor: LIGHT_BLUE_COLOR2,
  },
});

export default useStyles;
