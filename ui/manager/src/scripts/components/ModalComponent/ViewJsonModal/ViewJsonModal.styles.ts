import { makeStyles } from '@material-ui/core/styles';
import {
  DARK_GREY_COLOR,
  GREY_COLOR,
} from '../../../../styles/DefaultStylingProperties';

const styles = makeStyles({
  conversation: {
    display: 'flex',
  },
  conversationRight: {
    marginLeft: 'auto',
  },
  header: {
    minHeight: '146px',
    backgroundColor: '#F7F9FB',
    paddingBottom: '10px',
  },
  topHeader: {
    display: 'flex',
    flex: 1,
    paddingTop: '44px',
    alignItems: 'center',
  },
  bottomHeader: {
    marginTop: '23px',
    display: 'flex',
    marginLeft: '50px',
  },
  descriptionContainer: {
    marginRight: '44px',
  },
  dateContainer: {
    minWidth: '125px',
    marginRight: '44px',
  },
  smallTitle: {
    color: GREY_COLOR,
    fontSize: '12px',
  },
  smallText: {
    color: DARK_GREY_COLOR,
    fontSize: '13px',
  },
  title: {
    color: DARK_GREY_COLOR,
    fontSize: '28px',
    height: '36px',
    marginLeft: '50px',
    marginRight: '8px',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
  },
  centerFlex: {
    flex: 1,
  },
  button: {
    width: '165px',
    marginRight: '18px',
  },
  close: {
    color: '#FFF',
    cursor: 'pointer',
    fontSize: '40px',
    height: '40px',
  },
  data: {
    color: GREY_COLOR,
    fontSize: '14px',
    backgroundColor: '#FFF',
    flex: 1,
    minHeight: '200px',
    minWidth: '500px',
    overflow: 'hidden',
    marginTop: '24px',
    marginLeft: '49px',
    marginRight: '29px',
    whiteSpace: 'pre-wrap',
    paddingBottom: '50px',
  },
  usedInContainer: {
    color: GREY_COLOR,
    fontSize: '13px',
    marginTop: '15px',
    marginLeft: '50px',
    marginRight: '30px',
  },
  options: {
    marginTop: 'auto',
    marginBottom: 'auto',
    marginRight: '5px',
    marginLeft: '5px',
  },
});
export default styles;
