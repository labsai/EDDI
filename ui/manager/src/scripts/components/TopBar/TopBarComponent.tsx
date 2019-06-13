import * as React from 'react';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import FilterComponent from './FilterComponent';
import NavigationComponent, { pageEnum } from './NavigationComponent';
import { CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import { ModalEnum } from '../utils/ModalEnum';
import BlueButton from '../Assets/Buttons/BlueButton';

const styles: CSSProperties = {
  createNewBotButton: {
    height: '36px',
    marginLeft: '32px',
    marginTop: '7px',
  },
  topBarCenter: {
    flex: '1',
  },
  topBarComponent: {
    display: 'flex',
    height: '50px',
    marginBottom: '15px',
    width: '100%',
  },
};

interface IProps {
  page: pageEnum;
  filter(text: string): void;
}

class TopBarComponent extends React.Component<IProps> {
  constructor(props) {
    super(props);
  }

  openModal = () => {
    switch (this.props.page) {
      case pageEnum.bot:
        ModalActionDispatchers.showModal(ModalEnum.createBot);
        return;
      case pageEnum.package:
        ModalActionDispatchers.showModal(ModalEnum.createPackage);
        return;
      default:
        return;
    }
  };

  render() {
    return (
      <div style={styles.topBarComponent}>
        <NavigationComponent page={this.props.page} />
        <div style={styles.topBarCenter} />
        <FilterComponent page={this.props.page} filter={this.props.filter} />
        <BlueButton
          text={`Create new ${pageEnum[this.props.page]}`}
          customStyles={styles.createNewBotButton}
          onClick={this.openModal}
          style={styles.createNewBot}
        />
      </div>
    );
  }
}

export default TopBarComponent;
