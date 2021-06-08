import { makeStyles } from '@material-ui/core/styles';
import {
  DARK_BLUE_COLOR,
  GREY_COLOR,
  LARGE_FONT,
  LIGHT_GREY_COLOR,
  LIGHT_GREY_COLOR3,
} from '../../../styles/DefaultStylingProperties';

const useStyles = makeStyles({
  pluginName: {
    color: DARK_BLUE_COLOR,
    fontSize: LARGE_FONT,
    marginRight: '10px',
    paddingTop: '6px',
    paddingBottom: '6px',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
  },
  centerFlex: {
    flex: 1,
  },
  modifiedDate: {
    color: GREY_COLOR,
    fontSize: '13px',
    marginTop: '10px',
  },
  content: {
    marginTop: '30px',
    borderBottom: `2px solid ${LIGHT_GREY_COLOR}`,
  },
  topContent: {
    '&:hover': {
      backgroundColor: LIGHT_GREY_COLOR3,
    },
    cursor: 'pointer',
    display: 'flex',
    paddingTop: '5px',
    paddingBottom: '5px',
  },
  bottomContent: {
    marginTop: '5px',
    marginRight: '50px',
  },
  text: {
    maxWidth: '600px',
  },
  truncate: {
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  descriptionContainer: {
    display: 'flex',
    color: GREY_COLOR,
    fontSize: '13px',
    maxWidth: '600px',
  },
  descriptionButton: {
    fontSize: '13px',
    color: DARK_BLUE_COLOR,
    whiteSpace: 'nowrap',
  },
  versionSelect: {
    position: 'relative',
    marginRight: '10px',
    marginTop: 'auto',
    marginBottom: 'auto',
  },
  options: {
    marginTop: 'auto',
    marginBottom: 'auto',
    marginRight: '5px',
  },
});

export default useStyles;
